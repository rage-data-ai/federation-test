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
                        new FlowNode("c", "end", "c", Map.of())),
                List.of(
                        new FlowEdge("a", "b", "default"),
                        new FlowEdge("a", "c", "error")));
        assertEquals("b", graph.next("a", "continue"));
        assertEquals("b", graph.next("a", "default"));
        // 无 clarify 边时回落到 default（大模型澄清→结束）
        assertEquals("b", graph.next("a", "clarify"));
        assertEquals("c", graph.next("a", "error"));
    }

    @Test
    void nextPrefersClarifyEdgeOverDefault() {
        FlowGraph graph = FlowGraph.of(
                "ontology",
                List.of(
                        new FlowNode("ontology", "ontology", "本体", Map.of()),
                        new FlowNode("metadata", "metadata", "元数据", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("ontology", "end", "clarify"),
                        new FlowEdge("ontology", "metadata", "default")));
        assertEquals("end", graph.next("ontology", "clarify"));
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
                        new FlowNode("c", "end", "c", Map.of())),
                List.of(new FlowEdge("a", "b", "continue")));
        assertEquals("b", graph.next("a", "default"));
        assertEquals("b", graph.next("a", "continue"));
    }

    @Test
    void validateRequiresEndAndKnownStart() {
        assertThrows(IllegalArgumentException.class,
                () -> FlowGraph.of("missing", List.of(new FlowNode("end", "end", "e", Map.of())), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowGraph("a", List.of(new FlowNode("a", "start", "a", Map.of())), List.of()).validate());
    }
}
