package com.cmcc.cmdevops.security.federation.ask;

import java.util.LinkedHashMap;
import java.util.Map;

/** 白盒阶段结果：边条件 + 写入变量池的出参。 */
public record AskStageResult(String outcome, Map<String, Object> outputs) {

    public AskStageResult {
        outcome = outcome == null || outcome.isBlank() ? "default" : outcome;
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public static AskStageResult of(String outcome) {
        return new AskStageResult(outcome, Map.of("outcome", outcome == null ? "default" : outcome));
    }

    public static AskStageResult of(String outcome, Map<String, Object> outputs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (outputs != null) {
            copy.putAll(outputs);
        }
        copy.putIfAbsent("outcome", outcome == null ? "default" : outcome);
        return new AskStageResult(outcome, copy);
    }
}
