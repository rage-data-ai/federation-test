package com.cmcc.cmdevops.security.federation.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.account.UserService.Session;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.BearerAuthFilter;
import com.cmcc.cmdevops.security.federation.security.CatalogAclService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RequireRole;
import com.cmcc.cmdevops.security.federation.security.TokenRevocationService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String BACKCHANNEL_SECRET_HEADER = "X-Backchannel-Secret";

    private final FederationProperties properties;
    private final CatalogAclService catalogAcl;
    private final TokenRevocationService revocations;
    private final UserService users;

    public AuthController(
            FederationProperties properties,
            CatalogAclService catalogAcl,
            TokenRevocationService revocations,
            UserService users) {
        this.properties = properties;
        this.catalogAcl = catalogAcl;
        this.revocations = revocations;
        this.users = users;
    }

    public record RegisterBody(String username, String password, String displayName) {
    }

    public record LoginBody(String username, String password) {
    }

    public record PasswordBody(String oldPassword, String newPassword) {
    }

    public record SetupBody(String username, String password, String displayName) {
    }

    /** 是否还需要首次管理员初始化。无需 Bearer。 */
    @GetMapping("/setup-status")
    public Map<String, Object> setupStatus() {
        return Map.of("needsSetup", users.needsSetup());
    }

    /** 首次启动创建管理员并登录。无需 Bearer；仅库中无账号时可用。 */
    @PostMapping("/setup")
    public Session setup(@RequestBody SetupBody body) {
        return users.bootstrapAdmin(body.username(), body.password(), body.displayName());
    }

    /** 自助注册本地账号，成功即登录。无需 Bearer。 */
    @PostMapping("/register")
    public Session register(@RequestBody RegisterBody body) {
        return users.register(body.username(), body.password(), body.displayName());
    }

    /** 用户名 + 密码登录，返回会话令牌。无需 Bearer。 */
    @PostMapping("/login")
    public Session login(@RequestBody LoginBody body) {
        return users.login(body.username(), body.password());
    }

    /** 本地账号改自己的密码；其他设备上的会话随之失效。 */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordBody body, HttpServletRequest request) {
        if (!isLocalAccount(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前身份不是本地账号，不能在这里改密码");
        }
        users.changePassword(GatewayUser.current().name(), body.oldPassword(), body.newPassword(), bearer(request));
        return Map.of("success", true);
    }

    /** 返回当前 Bearer / JWT 解析后的身份。鉴权始终开启。 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        GatewayUser user = GatewayUser.current();
        boolean localAccount = isLocalAccount(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", user.name());
        body.put("displayName", localAccount
                ? users.find(user.name()).map(view -> view.displayName()).orElse(user.name())
                : user.name());
        body.put("localAccount", localAccount);
        body.put("roles", user.roles().stream().map(GatewayRole::name).sorted().collect(Collectors.toList()));
        body.put("securityEnabled", true);
        body.put("needsSetup", users.needsSetup());
        body.put("jwtEnabled", properties.getSecurity().getJwt().isEnabled());
        body.put("catalogAcl", catalogAcl.isEnforced());
        body.put("catalogs", catalogAcl.currentGrants().stream().sorted().collect(Collectors.toList()));
        return body;
    }

    /** 吊销当前请求的 Bearer（登出）；后续同 token 返回 401。 */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String token = bearer(request);
        if (token != null) {
            users.logout(token);
            revocations.revoke(token);
        }
        return Map.of("success", true, "revoked", token != null);
    }

    /**
     * 管理员吊销任意 token（body.token）或当前 token。
     * 用于配合 IdP / 应急吊销。
     */
    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/revoke")
    public Map<String, Object> revoke(@RequestBody(required = false) Map<String, String> body, HttpServletRequest request) {
        String token = body != null ? body.get("token") : null;
        if (token == null || token.isBlank()) {
            token = bearer(request);
        }
        if (token == null || token.isBlank()) {
            return Map.of("success", false, "message", "未提供 token");
        }
        revocations.revoke(token.trim());
        return Map.of("success", true, "revoked", true, "denylistSize", revocations.size());
    }

    /**
     * OIDC / IdP back-channel logout。
     * 鉴权：ADMIN Bearer，或配置了 {@code federation.security.backchannel-logout-secret}
     * 时的 {@code X-Backchannel-Secret} 头。
     * Body：{@code token}（原始 access_token）或 {@code fingerprint}（SHA-256 hex）。
     */
    @PostMapping("/backchannel-logout")
    public Map<String, Object> backchannelLogout(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        assertBackchannelAuthorized(request);
        if (body == null || body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 token 或 fingerprint");
        }
        String token = body.get("token");
        String fingerprint = body.get("fingerprint");
        if (token != null && !token.isBlank()) {
            revocations.revoke(token.trim());
        } else if (fingerprint != null && !fingerprint.isBlank()) {
            revocations.revokeFingerprint(fingerprint.trim());
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 token 或 fingerprint");
        }
        return Map.of("success", true, "revoked", true, "denylistSize", revocations.size());
    }

    private void assertBackchannelAuthorized(HttpServletRequest request) {
        String configured = properties.getSecurity().getBackchannelLogoutSecret();
        if (configured != null && !configured.isBlank()) {
            String provided = request.getHeader(BACKCHANNEL_SECRET_HEADER);
            if (configured.equals(provided)) {
                return;
            }
        }
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "back-channel logout 未授权");
    }

    private static boolean isLocalAccount(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(BearerAuthFilter.LOCAL_ACCOUNT_ATTRIBUTE));
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
