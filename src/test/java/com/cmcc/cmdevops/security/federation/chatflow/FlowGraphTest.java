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
    void nextPrefersExactOutcomeThenDefault() {
        FlowGraph graph = FlowGraph.of(
                "a",
                List.of(
                        new FlowNode("a", "start", "a", Map.of()),
                        new FlowNode("b", "llm_chat", "b", Map.of()),
                        new FlowNode("c", "end", "c", Map.of())),
                List.of(
                        new FlowEdge("a", "b", "continue"),
                        new FlowEdge("a", "c", "default")));
        assertEquals("b", graph.next("a", "continue"));
        assertEquals("c", graph.next("a", "answered"));
    }

    @Test
    void validateRequiresEndAndKnownStart() {
        assertThrows(IllegalArgumentException.class,
                () -> FlowGraph.of("missing", List.of(new FlowNode("end", "end", "e", Map.of())), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowGraph("a", List.of(new FlowNode("a", "start", "a", Map.of())), List.of()).validate());
    }
}
