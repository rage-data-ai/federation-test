package com.cmcc.cmdevops.security.federation.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 按用户限制可见/可查询的 catalog。配置 {@code federation.security.catalogs} 非空时生效：
 * 值为 {@code *} 表示全部；逗号分隔名称；未出现的用户不允许任何 catalog。
 * {@link GatewayRole#ADMIN} 绕过限制。鉴权关闭时不限制。
 */
@Component
public class CatalogAclService {

    private final FederationProperties properties;

    public CatalogAclService(FederationProperties properties) {
        this.properties = properties;
    }

    public boolean isEnforced() {
        return properties.getSecurity().isEnabled() && !properties.getSecurity().getCatalogs().isEmpty();
    }

    public boolean allows(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return true;
        }
        if (!isEnforced()) {
            return true;
        }
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return true;
        }
        Set<String> allowed = allowedCatalogs(user.name());
        if (allowed.contains("*")) {
            return true;
        }
        return allowed.contains(catalog.trim().toLowerCase(Locale.ROOT));
    }

    public void assertAllowed(Collection<String> catalogs) {
        if (!isEnforced() || catalogs == null || catalogs.isEmpty()) {
            return;
        }
        for (String catalog : catalogs) {
            if (!allows(catalog)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "无权访问数据源: " + catalog);
            }
        }
    }

    public <T> java.util.List<T> filter(java.util.List<T> items, Function<T, String> catalogName) {
        if (!isEnforced() || items == null || items.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> allows(catalogName.apply(item))).toList();
    }

    private Set<String> allowedCatalogs(String username) {
        Map<String, String> catalogs = properties.getSecurity().getCatalogs();
        String raw = catalogs.get(username);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        if ("*".equals(raw.trim())) {
            return Set.of("*");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 调试 /auth/me 展示用。 */
    public Set<String> currentGrants() {
        if (!isEnforced()) {
            return Set.of("*");
        }
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return Set.of("*");
        }
        Set<String> allowed = allowedCatalogs(user.name());
        return allowed.isEmpty() ? Set.of() : Set.copyOf(allowed);
    }

    @Override
    public String toString() {
        return "CatalogAclService{enforced=" + isEnforced() + ", grants="
                + currentGrants().stream().sorted().collect(Collectors.joining(",")) + "}";
    }
}
