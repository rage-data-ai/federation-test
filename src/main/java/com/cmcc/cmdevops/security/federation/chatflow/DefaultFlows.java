package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;

/** 内置流程模板。 */
public final class DefaultFlows {

    public static final String TEMPLATE_BUILTIN_ASK = "builtin_ask";
    public static final String TEMPLATE_LLM_CHAT = "llm_chat";

    public static final String DEFAULT_APP_ID = "app-builtin-ask";
    public static final String DEFAULT_FLOW_ID = "flow-builtin-ask";

    /** 内置问数图修订号；迁移器据此强制升级。 */
    public static final int BUILTIN_ASK_REVISION = 23;

    /** 大模型+SQL：只引用上游本体 / 元数据出参（运行时再叠内置 SQL 规则）。 */
    public static final String SQL_LLM_PROMPT = """
            基于上游节点输出规划并执行联邦 SQL。
            本体上下文：
            {{#本体.context#}}
            元数据：
            {{#元数据.report#}}
            """;

    /**
     * 知识判定：只引用上游知识库.result；写出 canAnswer/answer 供下游条件节点。
     */
    public static final String KB_JUDGE_PROMPT = """
            你是问数助手。根据上游知识库召回判断能否直接作答。
            上游召回（知识库.result，含 id/question/answer/sql/caliber/evidence/year/version）：
            {{#知识库.result#}}
            判断是否能仅凭这些知识直接回答当前问题：
            - 能：复用或微调知识中的结论；不要编造知识里没有的数字。
            - 不能：时间范围、过滤条件、口径不符，或需要重新查库。
            请只输出一个 JSON 对象（不要其它文字）。本节点写出字段：canAnswer、answer（及可选 sql/knowledgeId/reason）：
            {"canAnswer":true,"answer":"中文结论","sql":"可空","knowledgeId":1}
            或
            {"canAnswer":false,"reason":"原因"}
            """;

    /**
     * 问数白盒相关节点（含条件）。{@code insight} 仅旧图兼容。
     */
    public static final Set<String> ASK_STAGE_TYPES =
            Set.of("knowledge", "ontology", "metadata", "insight", "if_else");

    private DefaultFlows() {
    }

    /**
     * 智能问数默认 DAG（模板名仍叫 builtin_ask，已无黑盒 builtin_ask 节点）：
     * 上游只写变量池；对话出口用回答节点自由拼接（对齐 Dify Answer）。
     * ChatFlow 无结束节点。
     *
     * <pre>
     * start → knowledge → llm_kb → if_kb
     *                                  ├─true  → answer_kb
     *                                  └─false → ontology → metadata ─error→（叶子）
     *                                                          └─default→ llm(sql) → answer_sql
     * </pre>
     */
    public static FlowGraph builtinAsk() {
        return FlowGraph.of(
                "start",
                List.of(
                        new FlowNode(
                                "start",
                                "start",
                                "开始",
                                Map.of(
                                        "x",
                                        40,
                                        "y",
                                        160,
                                        "memoryWindowTurns",
                                        12,
                                        "builtinAskRevision",
                                        BUILTIN_ASK_REVISION)),
                        new FlowNode(
                                "knowledge",
                                "knowledge",
                                "知识库",
                                Map.of(
                                        "x",
                                        220,
                                        "y",
                                        160,
                                        "topK",
                                        5,
                                        "inputs",
                                        Map.of("query", "{{#sys.query#}}", "topK", 5))),
                        new FlowNode(
                                "llm_kb",
                                "llm_chat",
                                "知识判定",
                                Map.of(
                                        "x",
                                        440,
                                        "y",
                                        160,
                                        "memoryWindowTurns",
                                        12,
                                        "systemPrompt",
                                        KB_JUDGE_PROMPT,
                                        "skills",
                                        List.of(),
                                        "inputs",
                                        Map.of(
                                                "query",
                                                "{{#sys.query#}}",
                                                "systemPrompt",
                                                KB_JUDGE_PROMPT,
                                                "skills",
                                                List.of()))),
                        new FlowNode(
                                "if_kb",
                                "if_else",
                                "能否用知识作答",
                                Map.of(
                                        "x",
                                        660,
                                        "y",
                                        160,
                                        "inputs",
                                        Map.of(
                                                "left",
                                                "{{#知识判定.canAnswer#}}",
                                                "op",
                                                "==",
                                                "right",
                                                true))),
                        new FlowNode(
                                "ontology",
                                "ontology",
                                "本体",
                                Map.of(
                                        "x",
                                        900,
                                        "y",
                                        260,
                                        "inputs",
                                        Map.of("query", "{{#sys.query#}}"))),
                        new FlowNode(
                                "metadata",
                                "metadata",
                                "元数据",
                                Map.of(
                                        "x",
                                        1120,
                                        "y",
                                        260,
                                        "recover",
                                        true,
                                        "inputs",
                                        Map.of("recover", true))),
                        new FlowNode(
                                "llm",
                                "llm_chat",
                                "大模型对话",
                                Map.of(
                                        "x",
                                        1340,
                                        "y",
                                        260,
                                        "memoryWindowTurns",
                                        12,
                                        "systemPrompt",
                                        SQL_LLM_PROMPT,
                                        "skills",
                                        List.of("sql", "preference"),
                                        "inputs",
                                        Map.of(
                                                "query",
                                                "{{#sys.query#}}",
                                                "systemPrompt",
                                                SQL_LLM_PROMPT,
                                                "skills",
                                                List.of("sql", "preference")))),
                        new FlowNode(
                                "answer_kb",
                                "answer",
                                "知识回答",
                                Map.of(
                                        "x",
                                        880,
                                        "y",
                                        40,
                                        "text",
                                        "{{#知识判定.answer#}}",
                                        "inputs",
                                        Map.of("text", "{{#知识判定.answer#}}"))),
                        new FlowNode(
                                "answer_sql",
                                "answer",
                                "回答",
                                Map.of(
                                        "x",
                                        1560,
                                        "y",
                                        260,
                                        "text",
                                        "{{#大模型对话.answer#}}",
                                        "inputs",
                                        Map.of("text", "{{#大模型对话.answer#}}")))),
                List.of(
                        new FlowEdge("start", "knowledge", "default"),
                        new FlowEdge("knowledge", "llm_kb", "default"),
                        new FlowEdge("llm_kb", "if_kb", "default"),
                        new FlowEdge("if_kb", "answer_kb", "true"),
                        new FlowEdge("if_kb", "ontology", "false"),
                        new FlowEdge("if_kb", "ontology", "default"),
                        // 本体澄清需显式边（clarify 不再回落 default）；大模型 clarify 为叶子
                        new FlowEdge("ontology", "metadata", "default"),
                        new FlowEdge("ontology", "metadata", "clarify"),
                        new FlowEdge("metadata", "llm", "default"),
                        new FlowEdge("llm", "answer_sql", "default"),
                        new FlowEdge("llm", "answer_sql", "answered")));
    }

    public static FlowGraph llmChat() {
        return FlowGraph.of(
                "start",
                List.of(
                        new FlowNode(
                                "start",
                                "start",
                                "开始",
                                Map.of("x", 40, "y", 120, "memoryWindowTurns", 12)),
                        new FlowNode(
                                "chat",
                                "llm_chat",
                                "大模型对话",
                                Map.of(
                                        "x",
                                        260,
                                        "y",
                                        120,
                                        "systemPrompt",
                                        "你是跨源联邦查询控制台里的对话助手。用简洁中文回答；不确定时说明假设。",
                                        "inputs",
                                        Map.of(
                                                "query",
                                                "{{#sys.query#}}",
                                                "systemPrompt",
                                                "你是跨源联邦查询控制台里的对话助手。用简洁中文回答；不确定时说明假设。",
                                                "skills",
                                                List.of()))),
                        new FlowNode(
                                "answer",
                                "answer",
                                "回答",
                                Map.of(
                                        "x",
                                        500,
                                        "y",
                                        120,
                                        "text",
                                        "{{#大模型对话.answer#}}",
                                        "inputs",
                                        Map.of("text", "{{#大模型对话.answer#}}")))),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "answer", "default")));
    }

    public static FlowGraph template(String name) {
        if (name == null || name.isBlank() || TEMPLATE_BUILTIN_ASK.equals(name)) {
            return builtinAsk();
        }
        if (TEMPLATE_LLM_CHAT.equals(name)) {
            return llmChat();
        }
        throw new IllegalArgumentException("未知流程模板：" + name + "（可用：builtin_ask、llm_chat）");
    }

    public static boolean isAskStageType(String type) {
        return type != null && ASK_STAGE_TYPES.contains(type);
    }

    /** 仍是一期「单节点 builtin_ask」图，需要迁移到新 DAG。 */
    public static boolean isLegacyBuiltinAskGraph(FlowGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return false;
        }
        boolean hasBuiltin = graph.nodes().stream().anyMatch(node -> "builtin_ask".equals(node.type()));
        boolean hasStage = graph.nodes().stream().anyMatch(node -> isAskStageType(node.type()));
        return hasBuiltin && !hasStage;
    }

    /** 旧四阶段（无 if_else）或修订号落后时升级。 */
    public static boolean needsIfElseKnowledgeUpgrade(FlowGraph graph) {
        return needsBuiltinAskGraphUpgrade(graph);
    }

    /**
     * 需要升级到「knowledge → llm_chat（提示词约定 canAnswer）→ if_else」的图。
     */
    public static boolean needsBuiltinAskGraphUpgrade(FlowGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return false;
        }
        if (!graph.nodes().stream().anyMatch(node -> "knowledge".equals(node.type()))) {
            return false;
        }
        Object rev = graph.node(graph.startNodeId()).config().get("builtinAskRevision");
        int current = rev instanceof Number number ? number.intValue() : 0;
        if (current < BUILTIN_ASK_REVISION) {
            return true;
        }
        if (graph.nodes().stream().anyMatch(node -> "end".equals(node.type()))) {
            return true;
        }
        // rev21：对话出口用回答节点；缺 answer_kb / answer_sql 则升级
        boolean hasAnswerKb = graph.nodes().stream().anyMatch(node -> "answer_kb".equals(node.id()));
        boolean hasAnswerSql = graph.nodes().stream().anyMatch(node -> "answer_sql".equals(node.id()));
        if (!hasAnswerKb || !hasAnswerSql) {
            return true;
        }
        boolean hasLlmChat = graph.nodes().stream().anyMatch(node -> "llm_chat".equals(node.type()));
        boolean hasIfElse = graph.nodes().stream().anyMatch(node -> "if_else".equals(node.type()));
        boolean knowledgeToIfElse = graph.edges().stream().anyMatch(edge -> {
            if (!"knowledge".equals(edge.from())) {
                return false;
            }
            try {
                return "if_else".equals(graph.node(edge.to()).type());
            } catch (RuntimeException e) {
                return false;
            }
        });
        boolean stillSpecialJudge = graph.nodes().stream()
                .anyMatch(node -> "llm_chat".equals(node.type())
                        && Boolean.TRUE.equals(node.config().get("knowledgeJudge")));
        return !hasLlmChat || !hasIfElse || knowledgeToIfElse || stillSpecialJudge;
    }
}
