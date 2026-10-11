package com.cmcc.cmdevops.security.federation.router;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

@Component
public class QueryRouter {

    private final CatalogRegistry catalogRegistry;

    public QueryRouter(CatalogRegistry catalogRegistry) {
        this.catalogRegistry = catalogRegistry;
    }

    public RoutePlan route(AnalyzedQuery analyzed, String sqlAfterRewrite) {
        Set<String> catalogs = analyzed.catalogs();
        for (String catalog : catalogs) {
            if ("system".equalsIgnoreCase(catalog)) {
                continue;
            }
            if (catalogRegistry.get(catalog) == null) {
                throw new IllegalArgumentException("未注册的数据源: " + catalog + "，请先在界面添加");
            }
        }
        String reason = catalogs.isEmpty()
                ? "交给 Trino"
                : "Trino 联邦 catalogs=" + catalogs;
        return new RoutePlan(EngineType.TRINO, null, catalogs, reason, sqlAfterRewrite);
    }
}
