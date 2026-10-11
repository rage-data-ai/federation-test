package com.cmcc.cmdevops.security.federation.model;

import java.util.List;

public record QueryResponse(
        boolean success,
        String engine,
        String routeReason,
        List<String> catalogs,
        String rewrittenSql,
        List<ColumnMeta> columns,
        List<List<Object>> rows,
        long rowCount,
        long elapsedMs,
        boolean cached,
        String message
) {
    public static QueryResponse fail(String message, long elapsedMs) {
        return new QueryResponse(false, null, null, List.of(), null, List.of(), List.of(), 0, elapsedMs, false, message);
    }

    public record ColumnMeta(String name, String type) {
    }

    public record ResultSetData(List<ColumnMeta> columns, List<List<Object>> rows) {
    }
}
