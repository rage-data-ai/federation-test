package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;

/** 内置流程模板。 */
public final class DefaultFlows {

    public static final String TEMPLATE_BUILTIN_ASK = "builtin_ask";
    public static final String TEMPLATE_LLM_CHAT = "llm_chat";

    public static final String DEFAULT_APP_ID = "app-builtin-ask";
    public static final String DEFAULT_FLOW_ID = "flow-builtin-ask";

    private DefaultFlows() {
    }

    public static FlowGraph builtinAsk() {
        return FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of("x", 40, "y", 120)),
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
                        new FlowNode("start", "start", "开始", Map.of("x", 40, "y", 120)),
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
                                        "你是跨源联邦查询控制台里的对话助手。用简洁中文回答；不确定时说明假设。")),
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
}
