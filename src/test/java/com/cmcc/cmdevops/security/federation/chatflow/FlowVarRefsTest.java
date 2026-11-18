package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;

class FlowVarRefsTest {

    @Test
    void resolvesSysAndNodeRefs() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("高危告警多少", List.of()), "alice");
        pool.putNode("knowledge", Map.of("answer", "共 12 条", "outcome", "answered"));
        assertEquals("高危告警多少", FlowVarRefs.resolve("{{#sys.query#}}", pool));
        assertEquals("共 12 条", FlowVarRefs.resolve("{{#knowledge.answer#}}", pool));
        assertEquals(
                "问：高危告警多少；答：共 12 条",
                FlowVarRefs.interpolate("问：{{#sys.query#}}；答：{{#knowledge.answer#}}", pool));
    }

    @Test
    void resolveInputsMergesDefaultsAndLiterals() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("q", List.of()), "alice");
        Map<String, Object> resolved = FlowVarRefs.resolveInputs(
                Map.of("query", "{{#sys.query#}}", "topK", 5),
                Map.of("topK", 8),
                pool);
        assertEquals("q", resolved.get("query"));
        assertEquals(8, resolved.get("topK"));
        assertTrue(FlowVarRefs.isRef("{{#sys.query#}}"));
    }

    @Test
    void interpolatesKnowledgeResultArrayAsJson() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("高危告警多少", List.of()), "alice");
        pool.putNode(
                "知识库",
                Map.of(
                        "result",
                        List.of(Map.of("id", 1, "question", "高危告警多少", "answer", "共 12 条"))));
        Object resolved = FlowVarRefs.resolve("{{#知识库.result#}}", pool);
        assertTrue(resolved instanceof List);
        String text = FlowVarRefs.interpolate("召回：{{#知识库.result#}}", pool);
        assertTrue(text.contains("\"question\":\"高危告警多少\""));
        assertTrue(text.contains("\"answer\":\"共 12 条\""));
        assertEquals("高危告警多少", FlowVarRefs.resolve("{{#知识库.result.0.question#}}", pool));
    }
}
