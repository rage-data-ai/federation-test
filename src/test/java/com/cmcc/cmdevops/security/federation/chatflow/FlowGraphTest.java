package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;

class FlowGraphTest {

    @Test
    void nextTreatsContinueAsDefaultButClarifyIsDistinct() {
        FlowGraph graph = FlowGraph.of(
                "a",
                List.of(
                        new FlowNode("a", "start", "a", Map.of()),
                        new FlowNode("b", "llm_chat", "b", Map.of()),
                        new FlowNode("c", "answer", "c", Map.of("inputs", Map.of("text", "x")))),
                List.of(
                        new FlowEdge("a", "b", "default"),
                        new FlowEdge("a", "c", "error")));
        assertEquals("b", graph.next("a", "continue"));
        assertEquals("b", graph.next("a", "default"));
        // 无 clarify 边时回落到 default
        assertEquals("b", graph.next("a", "clarify"));
        assertEquals("c", graph.next("a", "error"));
        // true 无边：叶子终止，不误走 default
        org.junit.jupiter.api.Assertions.assertNull(graph.next("a", "true"));
    }

    @Test
    void nextPrefersClarifyEdgeOverDefault() {
        FlowGraph graph = FlowGraph.of(
                "ontology",
                List.of(
                        new FlowNode("ontology", "ontology", "本体", Map.of()),
                        new FlowNode("metadata", "metadata", "元数据", Map.of()),
                        new FlowNode(
                                "ans",
                                "answer",
                                "澄清回答",
                                Map.of("inputs", Map.of("text", "{{#本体.context#}}")))),
                List.of(
                        new FlowEdge("ontology", "ans", "clarify"),
                        new FlowEdge("ontology", "metadata", "default")));
        assertEquals("ans", graph.next("ontology", "clarify"));
        assertEquals("metadata", graph.next("ontology", "continue"));
        assertEquals("metadata", graph.next("ontology", "default"));
    }

    @Test
    void nextMapsLegacyContinueEdgesToDefault() {
        FlowGraph graph = FlowGraph.of(
                "a",
                List.of(
                        new FlowNode("a", "start", "a", Map.of()),
                        new FlowNode("b", "llm_chat", "b", Map.of()),
                        new FlowNode("c", "answer", "c", Map.of("inputs", Map.of("text", "x")))),
                List.of(
                        new FlowEdge("a", "b", "continue"),
                        new FlowEdge("b", "c", "default")));
        assertEquals("b", graph.next("a", "default"));
        assertEquals("b", graph.next("a", "continue"));
    }

    @Test
    void validateRequiresChatExitAndKnownStart() {
        assertThrows(IllegalArgumentException.class,
                () -> FlowGraph.of("missing", List.of(new FlowNode("ans", "answer", "e", Map.of())), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowGraph("a", List.of(new FlowNode("a", "start", "a", Map.of())), List.of()).validate());
    }

    @Test
    void ofConvertsLegacyEndToAnswer() {
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode("chat", "llm_chat", "对话", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "end", "default")));
        assertEquals(0, graph.nodes().stream().filter(n -> "end".equals(n.type())).count());
        assertEquals(1, graph.nodes().stream().filter(n -> "answer".equals(n.type())).count());
    }

    @Test
    void validateRequiresAnswerNode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlowGraph(
                                "start",
                                List.of(
                                        new FlowNode("start", "start", "开始", Map.of()),
                                        new FlowNode("chat", "llm_chat", "对话", Map.of())),
                                List.of(new FlowEdge("start", "chat", "default")))
                        .validate());
    }
}
