package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点类型的入参 / 出参端口目录（对齐 Dify Inputs / Outputs）。
 */
public final class FlowNodePorts {

    public record Port(String name, String label, String description, String dataType, List<Port> fields) {
        public Port(String name, String label, String description) {
            this(name, label, description, "Any", List.of());
        }

        public Port(String name, String label, String description, String dataType) {
            this(name, label, description, dataType, List.of());
        }
    }

    public record Spec(List<Port> inputs, List<Port> outputs, Map<String, Object> defaultInputs) {
    }

    /** 知识库 result 条目字段：对齐 fed_ask_knowledge + 召回分。 */
    public static final List<Port> KNOWLEDGE_RESULT_FIELDS = List.of(
            new Port("id", "ID", "主键", "Number"),
            new Port("score", "分数", "召回相似度", "Number"),
            new Port("question", "问题", "知识问题", "String"),
            new Port("answer", "答案", "问答对文本", "String"),
            new Port("sql", "SQL", "库字段 sql_text", "String"),
            new Port("caliber", "口径", "口径摘要", "Array"),
            new Port("evidence", "证据", "证据 JSON", "Object"),
            new Port("kind", "类型", "smart_ask / general", "String"),
            new Port("status", "状态", "pending / approved / rejected", "String"),
            new Port("year", "年份", "业务年", "String"),
            new Port("version", "版本", "知识版本", "String"),
            new Port("sourceRunId", "来源运行", "来源问数 run", "String"));

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        SPECS.put(
                "start",
                new Spec(
                        List.of(),
                        List.of(
                                new Port("query", "问题", "写入 sys.query", "String"),
                                new Port("history", "历史", "写入 sys.history", "Array"),
                                new Port("conversation_id", "会话", "写入 sys.conversation_id", "String")),
                        Map.of()));
        SPECS.put(
                "knowledge",
                new Spec(
                        List.of(
                                new Port("query", "查询文本", "默认绑定 sys.query", "String"),
                                new Port("topK", "TopK", "召回条数", "Number")),
                        List.of(
                                new Port(
                                        "result",
                                        "检索结果",
                                        "Array[Object]，字段对齐知识表",
                                        "Array[Object]",
                                        KNOWLEDGE_RESULT_FIELDS)),
                        Map.of("query", "{{#sys.query#}}", "topK", 5)));
        SPECS.put(
                "if_else",
                new Spec(
                        List.of(
                                new Port("left", "左值", "可绑 {{#llm_kb.canAnswer#}}", "Any"),
                                new Port("op", "运算符", "> >= < <= == != empty not_empty", "String"),
                                new Port("right", "右值", "字面量或变量", "Any")),
                        List.of(
                                new Port("outcome", "结果条件", "true / false", "String"),
                                new Port("result", "布尔结果", "boolean", "Boolean")),
                        Map.of("left", "{{#知识判定.canAnswer#}}", "op", "==", "right", true)));
        SPECS.put(
                "ontology",
                new Spec(
                        List.of(new Port("query", "查询文本", "默认绑定 sys.query", "String")),
                        List.of(
                                new Port(
                                        "outcome",
                                        "分流条件",
                                        "default=继续（澄清默认也走这条）；可另画 clarify",
                                        "String"),
                                new Port(
                                        "context",
                                        "业务上下文",
                                        "概念摘要；澄清时含 clarify.question/options",
                                        "Object"),
                                new Port("answerability", "可回答性", "覆盖率与缺口预检", "Object")),
                        Map.of("query", "{{#sys.query#}}")));
        SPECS.put(
                "metadata",
                new Spec(
                        List.of(new Port("recover", "自动恢复", "缺表时重采元数据", "Boolean")),
                        List.of(
                                new Port("outcome", "分流条件", "default=默认 / error=出错", "String"),
                                new Port("report", "核对报告", "表列核对摘要，供下游引用", "Object"),
                                new Port("tables", "表数量", "可用表个数", "Number")),
                        Map.of("recover", true)));
        SPECS.put(
                "insight",
                new Spec(
                        List.of(
                                new Port("query", "查询文本", "默认绑定 sys.query", "String"),
                                new Port("skills", "工具技能", "sql / preference", "Array")),
                        List.of(
                                new Port("outcome", "分流条件", "answered / clarify / error", "String"),
                                new Port("answer", "答案", "澄清时含 clarify.question/options", "Any"),
                                new Port("sql", "SQL", "", "String"),
                                new Port("rowCount", "行数", "", "Number")),
                        Map.of("query", "{{#sys.query#}}", "skills", List.of("sql", "preference"))));
        SPECS.put(
                "llm_chat",
                new Spec(
                        List.of(
                                new Port("query", "用户消息", "默认绑定 sys.query", "String"),
                                new Port(
                                        "systemPrompt",
                                        "系统提示词",
                                        "可插入 {{#本体.context#}} / {{#元数据.report#}} / {{#知识库.result#}}",
                                        "String"),
                                new Port("memoryWindowTurns", "记忆轮数", "覆盖开始节点；0=无历史", "Number"),
                                new Port("skills", "工具技能", "勾选 sql 即联邦查询（原 SQL 洞察）", "Array")),
                        List.of(
                                new Port(
                                        "answer",
                                        "回复",
                                        "文本或结构化；澄清时含 clarify.question/options",
                                        "Any")),
                        Map.of(
                                "query",
                                "{{#sys.query#}}",
                                "systemPrompt",
                                "你是有帮助的中文助手。提示词中可插入 {{#知识库.result#}} 等变量。",
                                "memoryWindowTurns",
                                12,
                                "skills",
                                List.of())));
        SPECS.put(
                "answer",
                new Spec(
                        List.of(new Port("text", "回答模板", "支持 {{#var#}}；直接输出为最终回答", "String")),
                        List.of(
                                new Port("outcome", "结果条件", "default", "String"),
                                new Port("answer", "回复文本", "", "String")),
                        Map.of("text", "{{#sys.query#}}")));
        SPECS.put(
                "builtin_ask",
                new Spec(
                        List.of(new Port("query", "查询文本", "默认绑定 sys.query", "String")),
                        List.of(new Port("outcome", "结果条件", "default", "String")),
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

    /** 系统变量（写入变量池的 sys.*），供前端插入变量。 */
    public static List<Map<String, String>> sysVars() {
        return List.of(
                Map.of("path", "sys.query", "label", "用户问题"),
                Map.of("path", "sys.history", "label", "对话历史"),
                Map.of("path", "sys.conversation_id", "label", "会话 ID"),
                Map.of("path", "sys.app_id", "label", "应用 ID"),
                Map.of("path", "sys.user", "label", "当前用户"));
    }

    /** 前端目录：端口定义以本类为准，避免前后端各维护一份。 */
    public static Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sysVars", sysVars());
        out.put("ports", all());
        return out;
    }
}
