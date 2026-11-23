package com.cmcc.cmdevops.security.federation.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bearer Token 鉴权：保护 {@code /api/**} 与非健康类 Actuator；
 * {@code /api/v1/health}、{@code /actuator/health*}、注册 / 登录 / 首次管理员初始化放行。
 * 令牌依次按静态配置、本地账号会话、IdP JWT 解析。角色见 {@link GatewayRole}。
 * {@code federation.security.enabled=false} 已废弃，不会关闭鉴权。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BearerAuthFilter extends OncePerRequestFilter {

    /** 请求属性：当前身份来自本地账号会话时为 {@code Boolean.TRUE}。 */
    public static final String LOCAL_ACCOUNT_ATTRIBUTE = "federation.auth.localAccount";

    private static final Set<String> OPEN_AUTH_POST_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/setup");

    private final FederationProperties properties;
    private final ObjectProvider<JwtAccessTokenService> jwtAccessTokens;
    private final ObjectProvider<UserService> users;
    private final TokenRevocationService revocations;

    public BearerAuthFilter(
            FederationProperties properties,
            ObjectProvider<JwtAccessTokenService> jwtAccessTokens,
            ObjectProvider<UserService> users,
            TokenRevocationService revocations) {
        this.properties = properties;
        this.jwtAccessTokens = jwtAccessTokens;
        this.users = users;
        this.revocations = revocations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        if (path.equals("/api/v1/health") || isPublicActuator(path)) {
            return true;
        }
        if (path.startsWith("/actuator")) {
            // metrics / prometheus 等需 Bearer；与 /api/** 同一套鉴权
            return false;
        }
        return !path.startsWith("/api/");
    }

    private static boolean isPublicActuator(String path) {
        return "/actuator/health".equals(path)
                || path.startsWith("/actuator/health/")
                || "/actuator/info".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            if (allowBackchannelWithoutBearer(request) || isOpenAuthRequest(request)) {
                GatewayUser.set(GatewayUser.ANONYMOUS);
                filterChain.doFilter(request, response);
                return;
            }
            String token = bearerOrCookie(request);
            if (token == null || token.isEmpty()) {
                unauthorized(response, "缺少 Authorization: Bearer <token> 或会话 Cookie");
                return;
            }
            if (revocations.isRevoked(token)) {
                unauthorized(response, "令牌已吊销");
                return;
            }
            GatewayUser user = resolveUser(token, request);
            if (user == null) {
                unauthorized(response, "无效的访问令牌");
                return;
            }
            GatewayUser.set(user);
            filterChain.doFilter(request, response);
        } finally {
            GatewayUser.clear();
        }
    }

    private static boolean isOpenAuthRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        if ("GET".equals(request.getMethod()) && "/api/v1/auth/setup-status".equals(path)) {
            return true;
        }
        return "POST".equals(request.getMethod()) && OPEN_AUTH_POST_PATHS.contains(path);
    }

    private boolean allowBackchannelWithoutBearer(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.equals("/api/v1/auth/backchannel-logout")) {
            return false;
        }
        String configured = properties.getSecurity().getBackchannelLogoutSecret();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String provided = request.getHeader("X-Backchannel-Secret");
        return configured.equals(provided);
    }

    private static String bearerOrCookie(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return SessionCookies.read(request);
    }

    private GatewayUser resolveUser(String token, HttpServletRequest request) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Map<String, String> tokens = properties.getSecurity().getTokens();
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (token.equals(entry.getValue())) {
                return new GatewayUser(entry.getKey(), rolesOf(entry.getKey()));
            }
        }
        if (JwtAccessTokenService.looksLikeJwt(token)) {
            JwtAccessTokenService jwt = jwtAccessTokens.getIfAvailable();
            return jwt == null ? null : jwt.authenticate(token);
        }
        UserService accounts = users.getIfAvailable();
        GatewayUser local = accounts == null ? null : accounts.authenticate(token);
        if (local != null) {
            request.setAttribute(LOCAL_ACCOUNT_ATTRIBUTE, Boolean.TRUE);
        }
        return local;
    }

    private Set<GatewayRole> rolesOf(String username) {
        String raw = properties.getSecurity().getRoles().get(username);
        if (raw == null || raw.isBlank()) {
            // 未显式配置 roles 时仅 QUERY，避免误授 ADMIN
            return Set.of(GatewayRole.QUERY);
        }
        EnumSet<GatewayRole> roles = EnumSet.noneOf(GatewayRole.class);
        for (String part : raw.split(",")) {
            GatewayRole role = GatewayRole.parse(part);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles.isEmpty() ? Set.of(GatewayRole.QUERY) : roles;
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"" + escape(message) + "\",\"message\":\"" + escape(message)
                + "\",\"success\":false}");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
