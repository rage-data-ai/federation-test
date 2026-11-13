package com.cmcc.cmdevops.security.federation.security;

/**
 * 当前请求的网关身份。未启用鉴权时为 {@link #ANONYMOUS}。
 */
public record GatewayUser(String name) {

    public static final GatewayUser ANONYMOUS = new GatewayUser("anonymous");

    private static final ThreadLocal<GatewayUser> CURRENT = new ThreadLocal<>();

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
