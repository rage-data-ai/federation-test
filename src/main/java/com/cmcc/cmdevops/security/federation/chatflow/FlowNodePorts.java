package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点类型的入参 / 出参端口目录（对齐 Dify Inputs / Outputs）。
 */
public final class FlowNodePorts {

    public record Port(String name, String label, String description) {
    }

    public record Spec(List<Port> inputs, List<Port> outputs, Map<String, Object> defaultInputs) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        SPECS.put(
                "start",
                new Spec(
                        List.of(),
                        List.of(
                                new Port("query", "问题", "写入 sys.query"),
                                new Port("history", "历史", "写入 sys.history"),
                                new Port("conversation_id", "会话", "写入 sys.conversation_id")),
                        Map.of()));
        SPECS.put(
                "knowledge",
                new Spec(
                        List.of(
                                new Port("query", "查询文本", "默认绑定 sys.query"),
                                new Port("topK", "TopK", "召回条数")),
                        List.of(
                                new Port("outcome", "结果条件", "answered / continue"),
                                new Port("answered", "是否短路", "boolean"),
                                new Port("answer", "答案文本", "短路时的回答"),
                                new Port("matches", "命中数", "召回条数")),
                        Map.of("query", "{{#sys.query#}}", "topK", 5)));
        SPECS.put(
                "ontology",
                new Spec(
                        List.of(
                                new Port("query", "查询文本", "默认绑定 sys.query"),
                                new Port("skills", "工具技能", "ontology 等")),
                        List.of(
                                new Port("outcome", "结果条件", "continue / clarify"),
                                new Port("context", "业务上下文", "BusinessContext 摘要"),
                                new Port("answerability", "可回答性", "预检结果")),
                        Map.of("query", "{{#sys.query#}}", "skills", List.of("ontology"))));
        SPECS.put(
                "metadata",
                new Spec(
                        List.of(new Port("recover", "自动恢复", "缺表时重采元数据")),
                        List.of(
                                new Port("outcome", "结果条件", "continue / error"),
                                new Port("report", "元数据报告", "MetadataReport 摘要"),
                                new Port("tables", "表数量", "可用表数")),
                        Map.of("recover", true)));
        SPECS.put(
                "insight",
                new Spec(
                        List.of(
                                new Port("query", "查询文本", "默认绑定 sys.query"),
                                new Port("skills", "工具技能", "sql / preference")),
                        List.of(
                                new Port("outcome", "结果条件", "answered / clarify / error"),
                                new Port("answer", "答案文本", ""),
                                new Port("sql", "SQL", ""),
                                new Port("rowCount", "行数", "")),
                        Map.of("query", "{{#sys.query#}}", "skills", List.of("sql", "preference"))));
        SPECS.put(
                "llm_chat",
                new Spec(
                        List.of(
                                new Port("query", "用户消息", "默认绑定 sys.query"),
                                new Port("systemPrompt", "系统提示词", "支持 {{#var#}}；勾选 SQL 时由洞察 Analyst 主导"),
                                new Port("memoryWindowTurns", "记忆轮数", "覆盖开始节点；0=无历史"),
                                new Port("skills", "工具技能", "勾选 sql 即联邦查询（原 SQL 洞察）")),
                        List.of(
                                new Port("outcome", "结果条件", "default；勾选 sql 时 answered/clarify/error"),
                                new Port("answer", "回复文本", ""),
                                new Port("sql", "SQL", "勾选 sql 时写出"),
                                new Port("rowCount", "行数", "勾选 sql 时写出")),
                        Map.of(
                                "query",
                                "{{#sys.query#}}",
                                "systemPrompt",
                                "你是有帮助的中文助手。",
                                "memoryWindowTurns",
                                12,
                                "skills",
                                List.of())));
        SPECS.put(
                "answer",
                new Spec(
                        List.of(new Port("text", "回答模板", "支持 {{#var#}}；直接输出为最终回答")),
                        List.of(
                                new Port("outcome", "结果条件", "default"),
                                new Port("answer", "回复文本", "")),
                        Map.of("text", "{{#sys.query#}}")));
        SPECS.put(
                "builtin_ask",
                new Spec(
                        List.of(new Port("query", "查询文本", "默认绑定 sys.query")),
                        List.of(new Port("outcome", "结果条件", "default")),
                        Map.of("query", "{{#sys.query#}}")));
        SPECS.put("end", new Spec(List.of(), List.of(), Map.of()));
    }

    private FlowNodePorts() {
    }

    public static Spec spec(String type) {
        return SPECS.getOrDefault(type, new Spec(List.of(), List.of(), Map.of()));
    }

    public static Map<String, Spec> all() {
        return Map.copyOf(SPECS);
    }
}
