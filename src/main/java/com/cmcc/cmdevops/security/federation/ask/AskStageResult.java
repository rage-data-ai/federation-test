package com.cmcc.cmdevops.security.federation.ask;

import java.util.LinkedHashMap;
import java.util.Map;

/** 白盒阶段结果：边条件 + 写入变量池的出参。 */
public record AskStageResult(String outcome, Map<String, Object> outputs) {

    public AskStageResult {
        outcome = outcome == null || outcome.isBlank() ? "default" : outcome;
        outputs = sanitize(outputs);
    }

    public static AskStageResult of(String outcome) {
        return new AskStageResult(outcome, Map.of("outcome", outcome == null ? "default" : outcome));
    }

    public static AskStageResult of(String outcome, Map<String, Object> outputs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
        }
        copy.putIfAbsent("outcome", outcome == null ? "default" : outcome);
        return new AskStageResult(outcome, copy);
    }

    private static Map<String, Object> sanitize(Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        outputs.forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return Map.copyOf(copy);
    }
}
