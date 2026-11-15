package com.cmcc.cmdevops.security.federation.account;

import java.util.EnumSet;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.security.GatewayRole;

/**
 * 网关本地账号。{@code passwordHash} 只在服务内部流转，对外一律用 {@link UserView}。
 * {@code lastLoginAt} 为 0 表示从未登录。
 */
public record UserAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        Set<GatewayRole> roles,
        String status,
        long createdAt,
        long updatedAt,
        long lastLoginAt) {

    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";

    public UserAccount {
        roles = roles == null || roles.isEmpty()
                ? Set.of(GatewayRole.QUERY)
                : Set.copyOf(EnumSet.copyOf(roles));
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }

    public UserAccount withId(long newId) {
        return new UserAccount(
                newId, username, displayName, passwordHash, roles, status, createdAt, updatedAt, lastLoginAt);
    }

    public UserAccount withPasswordHash(String newHash, long now) {
        return new UserAccount(id, username, displayName, newHash, roles, status, createdAt, now, lastLoginAt);
    }

    public UserAccount withLastLoginAt(long now) {
        return new UserAccount(id, username, displayName, passwordHash, roles, status, createdAt, updatedAt, now);
    }

    public UserAccount withProfile(String newDisplayName, Set<GatewayRole> newRoles, String newStatus, long now) {
        return new UserAccount(
                id, username, newDisplayName, passwordHash, newRoles, newStatus, createdAt, now, lastLoginAt);
    }
}
