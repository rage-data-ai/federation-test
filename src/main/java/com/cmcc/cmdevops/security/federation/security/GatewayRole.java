package com.cmcc.cmdevops.security.federation.security;

import java.util.Locale;
import java.util.Set;

public enum GatewayRole {
    /** 执行查询 / 问数 / 只读 API */
    QUERY,
    /** 管理数据源、模型、本体写入 */
    ADMIN;

    public static GatewayRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return GatewayRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static final Set<GatewayRole> ALL = Set.of(values());
}
