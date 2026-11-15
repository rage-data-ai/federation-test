package com.cmcc.cmdevops.security.federation.account;

import java.util.List;

import com.cmcc.cmdevops.security.federation.security.GatewayRole;

/** 账号对外的样子：不含密码哈希。 */
public record UserView(
        long id,
        String username,
        String displayName,
        List<String> roles,
        String status,
        long createdAt,
        long lastLoginAt) {

    public UserView {
        roles = List.copyOf(roles);
    }

    public static UserView of(UserAccount account) {
        return new UserView(
                account.id(),
                account.username(),
                account.displayName(),
                account.roles().stream().map(GatewayRole::name).sorted().toList(),
                account.status(),
                account.createdAt(),
                account.lastLoginAt());
    }
}
