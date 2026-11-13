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
import com.cmcc.cmdevops.security.federation.model.TableRef;

/**
 * 按用户限制可见/可查询的 catalog 与表。配置 {@code federation.security.catalogs} 非空时生效：
 * <ul>
 *   <li>{@code *} — 全部</li>
 *   <li>{@code mysql_shop} 或 {@code mysql_shop.*} — 该 catalog 下全部表</li>
 *   <li>{@code mysql_shop.public.orders} — 指定三部分表名</li>
 * </ul>
 * 未出现的用户不允许任何资源；{@link GatewayRole#ADMIN} 绕过。鉴权关闭时不限制。
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
        Set<String> grants = grantsOf(user.name());
        if (grants.contains("*")) {
            return true;
        }
        String key = catalog.trim().toLowerCase(Locale.ROOT);
        if (grants.contains(key) || grants.contains(key + ".*")) {
            return true;
        }
        for (String grant : grants) {
            if (grant.startsWith(key + ".")) {
                return true;
            }
        }
        return false;
    }

    public boolean allowsTable(TableRef table) {
        if (table == null) {
            return true;
        }
        if (!isEnforced()) {
            return true;
        }
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return true;
        }
        Set<String> grants = grantsOf(user.name());
        if (grants.contains("*")) {
            return true;
        }
        String catalog = nullToEmpty(table.catalog()).toLowerCase(Locale.ROOT);
        String schema = nullToEmpty(table.schema()).toLowerCase(Locale.ROOT);
        String name = nullToEmpty(table.table()).toLowerCase(Locale.ROOT);
        if (catalog.isEmpty()) {
            return true;
        }
        if (grants.contains(catalog) || grants.contains(catalog + ".*")) {
            return true;
        }
        String qualified = catalog + "." + schema + "." + name;
        if (grants.contains(qualified)) {
            return true;
        }
        return grants.contains(catalog + "." + schema + ".*");
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

    public void assertAllowedTables(Collection<TableRef> tables) {
        if (!isEnforced() || tables == null || tables.isEmpty()) {
            return;
        }
        for (TableRef table : tables) {
            if (!allowsTable(table)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "无权访问表: " + table.qualifiedName());
            }
        }
    }

    public <T> java.util.List<T> filter(java.util.List<T> items, Function<T, String> catalogName) {
        if (!isEnforced() || items == null || items.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> allows(catalogName.apply(item))).toList();
    }

    private Set<String> grantsOf(String username) {
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
        Set<String> allowed = grantsOf(user.name());
        return allowed.isEmpty() ? Set.of() : Set.copyOf(allowed);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "CatalogAclService{enforced=" + isEnforced() + ", grants="
                + currentGrants().stream().sorted().collect(Collectors.joining(",")) + "}";
    }
}
