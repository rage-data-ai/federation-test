package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskStageResult;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class FlowAnswerNodeTest {

    @Test
    void usesTopLevelConfigTextWhenInputsMissing() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("用户问题", List.of()), "alice");
        FlowNode node = new FlowNode("ans", "answer", "回答", Map.of("text", "固定：TOP"));
        List<String> events = new ArrayList<>();
        Map<String, Object> answer = new HashMap<>();
        AskStageResult result = FlowRunner.runAnswerNode(node, Map.of(), pool, (event, data) -> {
            events.add(event);
            if ("answer".equals(event) && data instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    answer.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        });
        assertEquals("固定：TOP", result.outputs().get("answer"));
        assertEquals("固定：TOP", answer.get("answer"));
        assertFalse(events.contains("error"));
    }

    @Test
    void nullAndMissingVarsBecomeEmptyWithoutError() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("q", List.of()), "alice");
        pool.putNode("上游", new HashMap<>() {
            {
                put("present", "有值");
                put("nil", null);
            }
        });
        FlowNode node = new FlowNode(
                "ans",
                "answer",
                "回答",
                Map.of(
                        "inputs",
                        Map.of(
                                "text",
                                "A={{#上游.present#}};B={{#上游.nil#}};C={{#上游.missing#}};D={{#不存在.x#}}")));
        List<String> events = new ArrayList<>();
        AskStageResult result = FlowRunner.runAnswerNode(node, Map.of(), pool, (event, data) -> events.add(event));
        assertEquals("A=有值;B=;C=;D=", result.outputs().get("answer"));
        assertFalse(events.contains("error"));
    }

    @Test
    void pureMissingRefDoesNotFallBackToQuery() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("用户问题", List.of()), "alice");
        FlowNode node = new FlowNode(
                "ans", "answer", "回答", Map.of("inputs", Map.of("text", "{{#missing.field#}}")));
        Map<String, Object> resolvedInputs = new HashMap<>();
        resolvedInputs.put("text", null);
        AskStageResult result = FlowRunner.runAnswerNode(node, resolvedInputs, pool, (e, d) -> {
        });
        assertEquals("", result.outputs().get("answer"));
    }

    @Test
    void answerMergesUpstreamSqlRowsFromTemplateRefs() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("q", List.of()), "alice");
        pool.putNode(
                "大模型对话",
                Map.of(
                        "answer",
                        "结论",
                        "sql",
                        "select 1",
                        "rows",
                        List.of(List.of("web-01", 5L)),
                        "caliber",
                        List.of(Map.of("item", "x"))));
        FlowNode node = new FlowNode(
                "ans",
                "answer",
                "回答",
                Map.of("inputs", Map.of("text", "{{#大模型对话.answer#}}")));
        Map<String, Object> answer = new HashMap<>();
        FlowRunner.runAnswerNode(node, Map.of(), pool, (event, data) -> {
            if ("answer".equals(event) && data instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    answer.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        });
        assertEquals("结论", answer.get("answer"));
        assertEquals("select 1", answer.get("sql"));
        assertEquals(List.of(List.of("web-01", 5L)), answer.get("rows"));
    }

    @Test
    void prefersInputsTextOverTopLevel() {
        FlowVariablePool pool = FlowVariablePool.fromRequest(new AskRequest("q", List.of()), "alice");
        FlowNode node = new FlowNode(
                "ans",
                "answer",
                "回答",
                Map.of("text", "TOP", "inputs", Map.of("text", "INP={{#sys.query#}}")));
        AskStageResult result = FlowRunner.runAnswerNode(node, Map.of(), pool, (e, d) -> {
        });
        assertEquals("INP=q", result.outputs().get("answer"));
    }

    @Test
    void previewAndProdBothTolerateNullSplice() {
        AskService askService = org.mockito.Mockito.mock(AskService.class);
        // beginRun 把外层 listener 包进 recorder：emit 仍能被测试看到
        org.mockito.Mockito.when(askService.beginRun(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    AskListener outer = invocation.getArgument(1);
                    AskRunRecorder recorder = org.mockito.Mockito.mock(AskRunRecorder.class);
                    org.mockito.Mockito.doAnswer(inv -> {
                                outer.emit(inv.getArgument(0), inv.getArgument(1));
                                return null;
                            })
                            .when(recorder)
                            .emit(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
                    return recorder;
                });
        FlowRunner runner = new FlowRunner(
                askService,
                org.mockito.Mockito.mock(KnowledgeTools.class),
                new FederationProperties());
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode(
                                "ans",
                                "answer",
                                "回答",
                                Map.of(
                                        "inputs",
                                        Map.of("text", "前={{#无.此#}};后={{#sys.query#}}")))),
                List.of(new FlowEdge("start", "ans", "default")));
        PublishedApp app = new PublishedApp("app-ans-null", "答", 1L, "fv", graph);
        List<String> previewEvents = new ArrayList<>();
        List<String> prodEvents = new ArrayList<>();
        Map<String, Object> previewAnswer = new HashMap<>();
        Map<String, Object> prodAnswer = new HashMap<>();
        GatewayUser.set(new GatewayUser("alice", java.util.Set.of(GatewayRole.QUERY)));
        try {
            runner.runPreview(app, new AskRequest("正式问", List.of()), (event, data) -> {
                previewEvents.add(event);
                if ("answer".equals(event) && data instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        previewAnswer.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            });
            runner.run(app, new AskRequest("正式问", List.of()), (event, data) -> {
                prodEvents.add(event);
                if ("answer".equals(event) && data instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        prodAnswer.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            });
        } finally {
            GatewayUser.clear();
        }
        assertEquals("前=;后=正式问", previewAnswer.get("answer"));
        assertEquals("前=;后=正式问", prodAnswer.get("answer"));
        assertFalse(previewEvents.contains("error"));
        assertFalse(prodEvents.contains("error"));
    }
}
