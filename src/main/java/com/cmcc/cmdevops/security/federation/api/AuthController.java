package com.cmcc.cmdevops.security.federation.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.CatalogAclService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RequireRole;
import com.cmcc.cmdevops.security.federation.security.TokenRevocationService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final FederationProperties properties;
    private final CatalogAclService catalogAcl;
    private final TokenRevocationService revocations;

    public AuthController(
            FederationProperties properties,
            CatalogAclService catalogAcl,
            TokenRevocationService revocations) {
        this.properties = properties;
        this.catalogAcl = catalogAcl;
        this.revocations = revocations;
    }

    /** 返回当前 Bearer / JWT 解析后的身份；鉴权关闭时为 anonymous。 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        GatewayUser user = GatewayUser.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", user.name());
        body.put("roles", user.roles().stream().map(GatewayRole::name).sorted().collect(Collectors.toList()));
        body.put("securityEnabled", properties.getSecurity().isEnabled());
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
            revocations.revoke(token);
        }
        return Map.of("success", true, "revoked", token != null);
    }

    /**
     * 管理员吊销任意 token（body.token）或当前 token。
     * 用于配合 IdP back-channel / 应急吊销。
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

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
