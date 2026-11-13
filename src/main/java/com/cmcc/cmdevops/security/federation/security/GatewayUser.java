package com.cmcc.cmdevops.security.federation.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * 当前请求的网关身份。未启用鉴权时为 {@link #ANONYMOUS}（具备全部角色，便于本地开发）。
 */
public record GatewayUser(String name, Set<GatewayRole> roles) {

    public static final GatewayUser ANONYMOUS = new GatewayUser("anonymous", GatewayRole.ALL);

    private static final ThreadLocal<GatewayUser> CURRENT = new ThreadLocal<>();

    public GatewayUser {
        roles = roles == null || roles.isEmpty()
                ? Set.of(GatewayRole.QUERY)
                : Set.copyOf(EnumSet.copyOf(roles));
    }

    public boolean hasRole(GatewayRole role) {
        return roles.contains(role);
    }

    public static void set(GatewayUser user) {
        CURRENT.set(user == null ? ANONYMOUS : user);
    }

    public static GatewayUser current() {
        GatewayUser user = CURRENT.get();
        return user == null ? ANONYMOUS : user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
