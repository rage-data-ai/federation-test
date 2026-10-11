package com.cmcc.cmdevops.security.federation.model;

import java.util.List;
import java.util.Set;

public record AnalyzedQuery(
        String originalSql,
        String normalizedSql,
        List<TableRef> tables,
        List<VectorSearchCall> vectorSearches,
        boolean explain
) {
    public Set<String> catalogs() {
        return tables.stream()
                .map(TableRef::catalog)
                .filter(catalog -> catalog != null && !catalog.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
