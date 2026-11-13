package com.cmcc.cmdevops.security.federation.router;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

@Component
public class QueryRouter {

    private static final Set<String> DIRECT_TYPES = Set.of("mysql", "postgresql", "postgres", "clickhouse", "doris");

    private final CatalogRegistry catalogRegistry;

    public QueryRouter(CatalogRegistry catalogRegistry) {
        this.catalogRegistry = catalogRegistry;
    }

    public RoutePlan route(AnalyzedQuery analyzed, String sqlAfterRewrite) {
        return route(analyzed, sqlAfterRewrite, null);
    }

    public RoutePlan route(AnalyzedQuery analyzed, String sqlAfterRewrite, String engineHint) {
        Set<String> catalogs = analyzed.catalogs();
        for (String catalog : catalogs) {
            if ("system".equalsIgnoreCase(catalog)) {
                continue;
            }
            if (catalogRegistry.get(catalog) == null) {
                throw new IllegalArgumentException("未注册的数据源: " + catalog + "，请先在界面添加");
            }
        }

        EngineType forced = parseHint(engineHint);
        if (forced == EngineType.SPARK) {
            return new RoutePlan(EngineType.SPARK, null, catalogs, "用户指定 Spark（尚未接入）", sqlAfterRewrite);
        }
        if (forced == EngineType.TRINO) {
            return new RoutePlan(EngineType.TRINO, null, catalogs, "用户指定 Trino", sqlAfterRewrite);
        }
        if (forced == EngineType.DIRECT) {
            String direct = requireSingleDirectCatalog(catalogs);
            return new RoutePlan(EngineType.DIRECT, direct, catalogs,
                    "用户指定直连 catalog=" + direct, sqlAfterRewrite);
        }

        String singleDirect = singleDirectCatalog(catalogs);
        if (singleDirect != null) {
            return new RoutePlan(EngineType.DIRECT, singleDirect, catalogs,
                    "单源直连 catalog=" + singleDirect, sqlAfterRewrite);
        }

        String reason = catalogs.isEmpty()
                ? "交给 Trino"
                : "Trino 联邦 catalogs=" + catalogs;
        return new RoutePlan(EngineType.TRINO, null, catalogs, reason, sqlAfterRewrite);
    }

    private String singleDirectCatalog(Set<String> catalogs) {
        String candidate = null;
        for (String catalog : catalogs) {
            if ("system".equalsIgnoreCase(catalog)) {
                continue;
            }
            CatalogRecord record = catalogRegistry.get(catalog);
            if (record == null || !supportsDirect(record)) {
                return null;
            }
            if (candidate == null) {
                candidate = record.getName();
            } else if (!candidate.equalsIgnoreCase(record.getName())) {
                return null;
            }
        }
        return candidate;
    }

    private String requireSingleDirectCatalog(Set<String> catalogs) {
        String direct = singleDirectCatalog(catalogs);
        if (direct == null) {
            throw new IllegalArgumentException("engine=direct 需要恰好一个可直连的 JDBC 数据源");
        }
        return direct;
    }

    static boolean supportsDirect(CatalogRecord record) {
        if (record == null || !record.isDirect()) {
            return false;
        }
        String type = record.getType() == null ? "" : record.getType().toLowerCase(Locale.ROOT);
        return DIRECT_TYPES.contains(type) && record.resolveDriver() != null;
    }

    private static EngineType parseHint(String engineHint) {
        if (engineHint == null || engineHint.isBlank()) {
            return null;
        }
        return switch (engineHint.trim().toLowerCase(Locale.ROOT)) {
            case "trino" -> EngineType.TRINO;
            case "direct" -> EngineType.DIRECT;
            case "spark" -> EngineType.SPARK;
            case "gateway" -> EngineType.GATEWAY;
            default -> throw new IllegalArgumentException("不支持的 engine: " + engineHint);
        };
    }
}
