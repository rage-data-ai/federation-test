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

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 可选 Bearer Token 鉴权。{@code federation.security.enabled=true} 时保护 {@code /api/**}，
 * 健康检查与 Actuator 放行。角色见 {@link GatewayRole}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BearerAuthFilter extends OncePerRequestFilter {

    private final FederationProperties properties;
    private final ObjectProvider<JwtAccessTokenService> jwtAccessTokens;
    private final TokenRevocationService revocations;

    public BearerAuthFilter(
            FederationProperties properties,
            ObjectProvider<JwtAccessTokenService> jwtAccessTokens,
            TokenRevocationService revocations) {
        this.properties = properties;
        this.jwtAccessTokens = jwtAccessTokens;
        this.revocations = revocations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        if (path.startsWith("/actuator") || path.equals("/api/v1/health")) {
            return true;
        }
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            if (!properties.getSecurity().isEnabled()) {
                GatewayUser.set(GatewayUser.ANONYMOUS);
                filterChain.doFilter(request, response);
                return;
            }
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                unauthorized(response, "缺少 Authorization: Bearer <token>");
                return;
            }
            String token = header.substring(7).trim();
            if (revocations.isRevoked(token)) {
                unauthorized(response, "令牌已吊销");
                return;
            }
            GatewayUser user = resolveUser(token);
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

    private GatewayUser resolveUser(String token) {
        if (token.isEmpty()) {
            return null;
        }
        Map<String, String> tokens = properties.getSecurity().getTokens();
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (token.equals(entry.getValue())) {
                return new GatewayUser(entry.getKey(), rolesOf(entry.getKey()));
            }
        }
        JwtAccessTokenService jwt = jwtAccessTokens.getIfAvailable();
        if (jwt != null && JwtAccessTokenService.looksLikeJwt(token)) {
            return jwt.authenticate(token);
        }
        return null;
    }

    private Set<GatewayRole> rolesOf(String username) {
        String raw = properties.getSecurity().getRoles().get(username);
        if (raw == null || raw.isBlank()) {
            return GatewayRole.ALL;
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
