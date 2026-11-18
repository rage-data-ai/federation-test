package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FlowNodePortsTest {

    @Test
    void catalogExposesKnowledgeResultOnly() {
        Map<String, Object> catalog = FlowNodePorts.catalog();
        assertFalse(((List<?>) catalog.get("sysVars")).isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, FlowNodePorts.Spec> ports = (Map<String, FlowNodePorts.Spec>) catalog.get("ports");
        FlowNodePorts.Spec knowledge = ports.get("knowledge");
        assertEquals(1, knowledge.outputs().size());
        assertEquals("result", knowledge.outputs().get(0).name());
        assertEquals("Array[Object]", knowledge.outputs().get(0).dataType());
        assertTrue(knowledge.outputs().get(0).fields().stream().anyMatch(p -> "question".equals(p.name())));
        FlowNodePorts.Spec llm = ports.get("llm_chat");
        assertTrue(llm.inputs().stream().noneMatch(p -> "knowledgeJudge".equals(p.name())));
        assertEquals(1, llm.outputs().size());
        assertEquals("answer", llm.outputs().get(0).name());
        assertTrue(llm.outputs().stream().noneMatch(p -> "canAnswer".equals(p.name())));
    }
}
