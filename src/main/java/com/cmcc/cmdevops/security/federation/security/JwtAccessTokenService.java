package com.cmcc.cmdevops.security.federation.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 可选 JWT 校验：配置 {@code federation.security.jwt.issuer-uri} 后，
 * Bearer 令牌若像 JWT 则按 JWKS 校验，并从 claim 映射 {@link GatewayRole}。
 */
@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${federation.security.jwt.issuer-uri:}')")
public class JwtAccessTokenService {

    private final JwtDecoder decoder;
    private final String rolesClaim;

    public JwtAccessTokenService(FederationProperties properties) {
        String issuer = properties.getSecurity().getJwt().getIssuerUri();
        this.decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        this.rolesClaim = properties.getSecurity().getJwt().getRolesClaim();
    }

    JwtAccessTokenService(JwtDecoder decoder, String rolesClaim) {
        this.decoder = decoder;
        this.rolesClaim = rolesClaim;
    }

    public GatewayUser authenticate(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                subject = jwt.getClaimAsString("preferred_username");
            }
            if (subject == null || subject.isBlank()) {
                return null;
            }
            return new GatewayUser(subject, rolesFrom(jwt));
        } catch (JwtException ex) {
            return null;
        }
    }

    private Set<GatewayRole> rolesFrom(Jwt jwt) {
        EnumSet<GatewayRole> roles = EnumSet.noneOf(GatewayRole.class);
        Object claim = jwt.getClaim(rolesClaim);
        if (claim == null) {
            claim = jwt.getClaim("scope");
        }
        if (claim instanceof String text) {
            for (String part : text.split("[\\s,]")) {
                addRole(roles, part);
            }
        } else if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                addRole(roles, String.valueOf(item));
            }
        }
        if (roles.isEmpty()) {
            return Set.of(GatewayRole.QUERY);
        }
        return roles;
    }

    private static void addRole(EnumSet<GatewayRole> roles, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring(5);
        }
        if ("ADMIN".equals(normalized) || "GATEWAY_ADMIN".equals(normalized)) {
            roles.add(GatewayRole.ADMIN);
            roles.add(GatewayRole.QUERY);
            return;
        }
        if ("QUERY".equals(normalized) || "GATEWAY_QUERY".equals(normalized) || "READ".equals(normalized)) {
            roles.add(GatewayRole.QUERY);
        }
    }

    static boolean looksLikeJwt(String token) {
        if (token == null) {
            return false;
        }
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        return first > 0 && second > first && token.indexOf('.', second + 1) < 0;
    }

    static List<String> demoClaims() {
        return List.of("roles", "scope");
    }
}
