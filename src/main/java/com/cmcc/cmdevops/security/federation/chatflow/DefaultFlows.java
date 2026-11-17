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

    /**
     * 问数阶段节点（白盒拆跑；按 outcome 选边）。
     * {@code insight} 保留作旧图兼容；新默认图末级已改为 {@code llm_chat}+sql。
     */
    public static final Set<String> ASK_STAGE_TYPES = Set.of("knowledge", "ontology", "metadata", "insight");

    private DefaultFlows() {
    }

    /**
     * 智能问数：Dify 式四阶段展开。
     *
     * <pre>
     * start → knowledge ─answered→ end
     *            └continue→ ontology ─clarify→ end
     *                          └continue→ metadata ─error→ end
     *                                        └continue→ llm（大模型+SQL）→ end
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
                                Map.of("x", 40, "y", 140, "memoryWindowTurns", 12)),
                        new FlowNode(
                                "knowledge",
                                "knowledge",
                                "知识库",
                                Map.of(
                                        "x",
                                        240,
                                        "y",
                                        140,
                                        "topK",
                                        5,
                                        "skills",
                                        List.of("knowledge"),
                                        "inputs",
                                        Map.of("query", "{{#sys.query#}}", "topK", 5))),
                        new FlowNode(
                                "ontology",
                                "ontology",
                                "本体",
                                Map.of(
                                        "x",
                                        460,
                                        "y",
                                        140,
                                        "skills",
                                        List.of("ontology"),
                                        "inputs",
                                        Map.of(
                                                "query",
                                                "{{#sys.query#}}",
                                                "skills",
                                                List.of("ontology")))),
                        new FlowNode(
                                "metadata",
                                "metadata",
                                "元数据",
                                Map.of(
                                        "x",
                                        680,
                                        "y",
                                        140,
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
                                        900,
                                        "y",
                                        140,
                                        "memoryWindowTurns",
                                        12,
                                        "systemPrompt",
                                        "你是安全运营数据分析师，基于本体与元数据规划并执行联邦 SQL。",
                                        "skills",
                                        List.of("sql", "preference"),
                                        "inputs",
                                        Map.of(
                                                "query",
                                                "{{#sys.query#}}",
                                                "skills",
                                                List.of("sql", "preference")))),
                        new FlowNode("end", "end", "结束", Map.of("x", 1120, "y", 140))),
                List.of(
                        new FlowEdge("start", "knowledge", "default"),
                        new FlowEdge("knowledge", "end", "answered"),
                        new FlowEdge("knowledge", "ontology", "continue"),
                        new FlowEdge("knowledge", "ontology", "default"),
                        new FlowEdge("ontology", "end", "clarify"),
                        new FlowEdge("ontology", "metadata", "continue"),
                        new FlowEdge("ontology", "metadata", "default"),
                        new FlowEdge("metadata", "end", "error"),
                        new FlowEdge("metadata", "llm", "continue"),
                        new FlowEdge("metadata", "llm", "default"),
                        new FlowEdge("llm", "end", "answered"),
                        new FlowEdge("llm", "end", "clarify"),
                        new FlowEdge("llm", "end", "error"),
                        new FlowEdge("llm", "end", "default")));
    }

    /** 旧版单节点智能问数（兼容已发布 Release）。 */
    public static FlowGraph legacyBuiltinAsk() {
        return FlowGraph.of(
                "start",
                List.of(
                        new FlowNode(
                                "start",
                                "start",
                                "开始",
                                Map.of("x", 40, "y", 120, "memoryWindowTurns", 12)),
                        new FlowNode("ask", "builtin_ask", "智能问数", Map.of("x", 260, "y", 120)),
                        new FlowNode("end", "end", "结束", Map.of("x", 500, "y", 120))),
                List.of(
                        new FlowEdge("start", "ask", "default"),
                        new FlowEdge("ask", "end", "default")));
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
                        new FlowNode("end", "end", "结束", Map.of("x", 500, "y", 120))),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "end", "default")));
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

    /** 仍是一期「单节点 builtin_ask」图，需要迁移到四阶段。 */
    public static boolean isLegacyBuiltinAskGraph(FlowGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return false;
        }
        boolean hasBuiltin = graph.nodes().stream().anyMatch(node -> "builtin_ask".equals(node.type()));
        boolean hasStage = graph.nodes().stream().anyMatch(node -> isAskStageType(node.type()));
        return hasBuiltin && !hasStage;
    }
}
