package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskFlowState;
import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.AskStageResult;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class FlowRunnerTest {

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void stageGraphRunsWhiteBoxPerNode() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", List.of())));
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("canAnswer", false, "answer", "no")));
        when(askService.runOntology(any(), any(), any(), any()))
                .thenReturn(AskStageResult.of("continue"));
        when(askService.runMetadata(any(), any(), any(), eq(true)))
                .thenReturn(AskStageResult.of("continue", Map.of("tables", 1)));
        when(askService.runInsight(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("answered", Map.of("answer", "ok", "sql", "select 1")));

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        List<String> events = new ArrayList<>();
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> events.add(event));

        verify(askService).runKnowledge(any(), eq(recorder), any(AskFlowState.class), eq(5));
        verify(askService).runLlmChat(any(), eq(recorder), any(), any(), any(), anyBoolean());
        verify(askService).runOntology(any(), eq(recorder), any(AskFlowState.class), any());
        verify(askService).runMetadata(any(), eq(recorder), any(AskFlowState.class), eq(true));
        verify(askService).runInsight(any(), eq(recorder), any(AskFlowState.class), any(), any(), anyBoolean());
        verify(askService).finishRun(recorder);
        assertTrue(events.contains("vars"));
    }

    @Test
    void llmJsonCanAnswerThenIfElseGoesToEnd() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        List<Map<String, Object>> hits = List.of(Map.of("id", 1L, "score", 0.9, "question", "q1"));
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", hits)));
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of(
                        "default", Map.of("canAnswer", true, "answer", "hit")));

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });

        verify(askService).runKnowledge(any(), any(), any(), anyInt());
        verify(askService).runLlmChat(any(), eq(recorder), any(), any(), any(), anyBoolean());
        verify(askService, never()).runOntology(any(), any(), any(), any());
        verify(askService, never()).runInsight(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void ontologyClarifyContinuesToMetadata() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", List.of())));
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("canAnswer", false)));
        when(askService.runOntology(any(), any(), any(), any()))
                .thenReturn(AskStageResult.of(
                        "clarify",
                        Map.of("clarify", Map.of("question", "哪类？", "options", List.of("告警")))));
        when(askService.runMetadata(any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("error", Map.of("tables", 0)));

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });

        verify(askService).runOntology(any(), eq(recorder), any(AskFlowState.class), any());
        verify(askService).runMetadata(any(), eq(recorder), any(AskFlowState.class), eq(true));
        verify(askService, never()).runInsight(any(), any(), any(), any(), any(), anyBoolean());
        verify(askService).finishRun(recorder);
    }

    @Test
    void ifElseComparesHitCount() {
        assertEquals("true", FlowRunner.evaluateIfElse(Map.of("left", 2, "op", ">", "right", 0)).outcome());
        assertEquals("false", FlowRunner.evaluateIfElse(Map.of("left", 0, "op", ">", "right", 0)).outcome());
        assertEquals("true", FlowRunner.evaluateIfElse(Map.of("left", List.of(1), "op", "not_empty")).outcome());
    }

    @Test
    void builtinAskNodeRejected() {
        FlowGraph legacy = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode("ask", "builtin_ask", "整段", Map.of()),
                        new FlowNode("ans_end", "answer", "回答", Map.of("inputs", Map.of("text", "{{#sys.query#}}")))),
                List.of(
                        new FlowEdge("start", "ask", "default"),
                        new FlowEdge("ask", "ans_end", "default")));
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-legacy", "旧问数", 1L, "fv-1", legacy);
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
                }));
        assertTrue(thrown.getMessage().contains("builtin_ask 已移除"));
    }

    @Test
    void llmChatEmitsViaAskService() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        AskListener[] outer = new AskListener[1];
        when(askService.beginRun(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            outer[0] = invocation.getArgument(1);
            outer[0].emit("run", Map.of("runId", "r1", "conversationId", "c1"));
            return recorder;
        });
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            outer[0].emit("answer", Map.of("answer", "你好"));
            return AskStageResult.of("default", Map.of("answer", "你好"));
        });

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        List<String> events = new ArrayList<>();
        PublishedApp app = new PublishedApp("app-2", "对话", 2L, "fv-2", DefaultFlows.llmChat());
        runner.run(app, new AskRequest("hi", List.of()), (event, data) -> events.add(event));
        assertTrue(events.contains("run"));
        assertTrue(events.contains("answer"));
        // 统一 DAG：整图单次 begin/finishRun
        verify(askService, org.mockito.Mockito.times(1)).finishRun(recorder);
    }

    @Test
    void llmChatWithSqlSkillDelegatesToInsight() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        when(askService.runOntology(any(), any(), any(), any())).thenReturn(AskStageResult.of("continue"));
        when(askService.runMetadata(any(), any(), any(), eq(true))).thenReturn(AskStageResult.of("continue"));
        when(askService.runInsight(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("answered", Map.of("answer", "n", "sql", "select 1", "rowCount", 1L)));
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode(
                                "chat",
                                "llm_chat",
                                "对话",
                                Map.of("skills", List.of("sql", "preference"), "systemPrompt", "hi")),
                        new FlowNode("ans_end", "answer", "回答", Map.of("inputs", Map.of("text", "{{#sys.query#}}")))),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "ans_end", "answered"),
                        new FlowEdge("chat", "ans_end", "default")));
        runner.run(
                new PublishedApp("app-sql-llm", "SQL对话", 1L, "fv", graph),
                new AskRequest("q", List.of()),
                (event, data) -> {
                });
        verify(askService).runInsight(any(), eq(recorder), any(AskFlowState.class), any(), eq("hi"), anyBoolean());
        verify(askService, never()).runLlmChat(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void llmChatMemoryWindowOverridesHistory() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("answer", "ok")));
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of("memoryWindowTurns", 50)),
                        new FlowNode(
                                "chat",
                                "llm_chat",
                                "对话",
                                Map.of("memoryWindowTurns", 2, "systemPrompt", "hi", "skills", List.of())),
                        new FlowNode("ans_end", "answer", "回答", Map.of("inputs", Map.of("text", "{{#sys.query#}}")))),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "ans_end", "default")));
        List<Turn> history = List.of(
                new Turn("user", "q1"),
                new Turn("assistant", "a1"),
                new Turn("user", "q2"),
                new Turn("assistant", "a2"),
                new Turn("user", "q3"),
                new Turn("assistant", "a3"));
        runner.run(
                new PublishedApp("app-llm-mem", "对话", 1L, "fv", graph),
                new AskRequest("q4", history),
                (event, data) -> {
                });
        verify(askService).runLlmChat(
                argThat(req -> {
                    assertEquals(2, req.history().size());
                    assertEquals("q3", req.history().get(0).content());
                    return true;
                }),
                any(),
                any(),
                any(),
                any(),
                anyBoolean());
    }

    @Test
    void startNodeMemoryWindowTrimsHistory() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), anyBoolean())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", List.of())));
        FederationProperties props = new FederationProperties();
        props.getAsk().setHistoryWindowTurns(50);
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), props);
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of("memoryWindowTurns", 2)),
                        new FlowNode("knowledge", "knowledge", "知识库", Map.of("topK", 5)),
                        new FlowNode("ans_end", "answer", "回答", Map.of("inputs", Map.of("text", "{{#sys.query#}}")))),
                List.of(
                        new FlowEdge("start", "knowledge", "default"),
                        new FlowEdge("knowledge", "ans_end", "default")));
        PublishedApp app = new PublishedApp("app-mem", "记忆", 1L, "fv-mem", graph);
        List<Turn> history = List.of(
                new Turn("user", "q1"),
                new Turn("assistant", "a1"),
                new Turn("user", "q2"),
                new Turn("assistant", "a2"),
                new Turn("user", "q3"),
                new Turn("assistant", "a3"));
        runner.run(app, new AskRequest("q4", history), (event, data) -> {
        });
        verify(askService).beginRun(
                argThat(req -> {
                    assertEquals(2, req.history().size());
                    assertEquals("q3", req.history().get(0).content());
                    assertEquals("a3", req.history().get(1).content());
                    return true;
                }),
                any(),
                eq(false));
    }

    /** Mock recorder 把 emit 转给外层 listener，模拟真实 AskRunRecorder 转发。 */
    private static AskRunRecorder forwardingRecorder(AskListener[] outer) {
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    AskListener target = outer[0];
                    if (target != null) {
                        target.emit(invocation.getArgument(0), invocation.getArgument(1));
                    }
                    return null;
                })
                .when(recorder)
                .emit(org.mockito.ArgumentMatchers.anyString(), any());
        return recorder;
    }

    @Test
    void freeAssembleStartToAnswerEmitsNodeDetails() {
        AskService askService = mock(AskService.class);
        AskListener[] outer = new AskListener[1];
        AskRunRecorder recorder = forwardingRecorder(outer);
        when(askService.beginRun(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            outer[0] = invocation.getArgument(1);
            return recorder;
        });
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode(
                                "ans",
                                "answer",
                                "回答",
                                Map.of("inputs", Map.of("text", "echo:{{#sys.query#}}")))),
                List.of(new FlowEdge("start", "ans", "default")));
        List<String> events = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        runner.run(
                new PublishedApp("app-free", "自由组装", 1L, "fv", graph),
                new AskRequest("hello", List.of()),
                (event, data) -> {
                    events.add(event);
                    payloads.add(data);
                });

        assertTrue(events.contains("node_start"));
        assertTrue(events.contains("node_finish"));
        assertTrue(events.contains("answer"));
        assertTrue(events.contains("vars"));
        long starts = events.stream().filter("node_start"::equals).count();
        long finishes = events.stream().filter("node_finish"::equals).count();
        assertEquals(2, starts);
        assertEquals(2, finishes);
        verify(askService, org.mockito.Mockito.times(1)).beginRun(any(), any(), eq(false));
        verify(askService, org.mockito.Mockito.times(1)).finishRun(recorder);

        @SuppressWarnings("unchecked")
        Map<String, Object> startPayload = (Map<String, Object>) payloads.get(events.indexOf("node_start"));
        assertEquals("start", startPayload.get("nodeId"));
        assertTrue(startPayload.containsKey("inputs"));

        int answerFinishIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if ("node_finish".equals(events.get(i))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> finish = (Map<String, Object>) payloads.get(i);
                if ("ans".equals(finish.get("nodeId"))) {
                    answerFinishIdx = i;
                    assertTrue(finish.containsKey("durationMs"));
                    assertTrue(finish.containsKey("outputs"));
                    assertEquals("default", finish.get("outcome"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> outs = (Map<String, Object>) finish.get("outputs");
                    assertEquals("echo:hello", outs.get("answer"));
                }
            }
        }
        assertTrue(answerFinishIdx >= 0);
    }

    @Test
    void freeAssembleLlmThenAnswerSingleRun() {
        AskService askService = mock(AskService.class);
        AskListener[] outer = new AskListener[1];
        AskRunRecorder recorder = forwardingRecorder(outer);
        when(askService.beginRun(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            outer[0] = invocation.getArgument(1);
            return recorder;
        });
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("answer", "hi")));
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode(
                                "chat",
                                "llm_chat",
                                "对话",
                                Map.of("skills", List.of(), "systemPrompt", "hi")),
                        new FlowNode(
                                "ans",
                                "answer",
                                "回答",
                                Map.of("inputs", Map.of("text", "{{#对话.answer#}}")))),
                List.of(
                        new FlowEdge("start", "chat", "default"),
                        new FlowEdge("chat", "ans", "default")));
        List<String> answers = new ArrayList<>();
        runner.run(
                new PublishedApp("app-llm-free", "对话组装", 1L, "fv", graph),
                new AskRequest("q", List.of()),
                (event, data) -> {
                    if ("answer".equals(event) && data instanceof Map<?, ?> map) {
                        answers.add(String.valueOf(map.get("answer")));
                    }
                });
        assertEquals(List.of("hi"), answers);
        verify(askService, org.mockito.Mockito.times(1)).beginRun(any(), any(), eq(false));
        verify(askService, org.mockito.Mockito.times(1)).finishRun(recorder);
        verify(askService).runLlmChat(any(), any(), any(), any(), any(), anyBoolean());
    }
}
