package com.cmcc.cmdevops.security.federation.model;

import java.util.List;

public record VectorSearchCall(
        String placeholder,
        String collection,
        String field,
        String vectorExpr,
        int k,
        String filter,
        String alias,
        String originalText
) {
    public List<Double> resolveVector(java.util.Map<String, Object> parameters) {
        String expr = vectorExpr == null ? "" : vectorExpr.trim();
        if (expr.startsWith(":")) {
            Object value = parameters.get(expr.substring(1));
            return toDoubles(value);
        }
        return parseArrayLiteral(expr);
    }

    @SuppressWarnings("unchecked")
    private static List<Double> toDoubles(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("vector_search 缺少向量参数");
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> ((Number) item).doubleValue()).toList();
        }
        if (value instanceof String text) {
            return parseArrayLiteral(text);
        }
        throw new IllegalArgumentException("不支持的向量参数类型: " + value.getClass().getSimpleName());
    }

    static List<Double> parseArrayLiteral(String expr) {
        String body = expr.trim();
        if (body.regionMatches(true, 0, "ARRAY", 0, 5)) {
            body = body.substring(5).trim();
        }
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("vector_search 向量为空");
        }
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }
}
