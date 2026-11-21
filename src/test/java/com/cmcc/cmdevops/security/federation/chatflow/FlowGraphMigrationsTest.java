package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;

class FlowGraphMigrationsTest {

    @Test
    void convertsEndToAnswerWhenGraphHasLlmButNoAnswer() {
        FlowGraph raw = new FlowGraph(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode("chat", "llm_chat", "对话", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "end", "default")));
        FlowGraph migrated = FlowGraphMigrations.normalize(raw);
        assertFalse(migrated.nodes().stream().anyMatch(n -> "end".equals(n.type())));
        assertEquals(1, migrated.nodes().stream().filter(n -> "answer".equals(n.type())).count());
        assertEquals("answer", migrated.node("end").type());
        assertTrue(migrated.edges().stream().anyMatch(e -> "chat".equals(e.from()) && "end".equals(e.to())));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) migrated.node("end").config().get("inputs");
        assertEquals("{{#对话.answer#}}", inputs.get("text"));
        migrated.validate();
    }

    @Test
    void convertsLoneEndToAnswerWhenNoReplyNodes() {
        FlowGraph raw = new FlowGraph(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(new FlowEdge("start", "end", "default")));
        FlowGraph migrated = FlowGraphMigrations.normalize(raw);
        assertEquals(1, migrated.nodes().stream().filter(n -> "answer".equals(n.type())).count());
        assertFalse(migrated.nodes().stream().anyMatch(n -> "end".equals(n.type())));
        migrated.validate();
    }

    @Test
    void syncsTopLevelAnswerTextIntoInputs() {
        FlowNode node = new FlowNode("ans", "answer", "回答", Map.of("text", "固定文案"));
        FlowNode normalized = FlowGraphMigrations.normalizeAnswerNode(node);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) normalized.config().get("inputs");
        assertEquals("固定文案", inputs.get("text"));
    }
}
