package com.cmcc.cmdevops.security.federation.model;

import java.util.List;
import java.util.Set;

public record RoutePlan(
        EngineType engine,
        String directCatalog,
        Set<String> catalogs,
        String reason,
        String rewrittenSql
) {
    public List<String> catalogList() {
        return List.copyOf(catalogs);
    }
}
