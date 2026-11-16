package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(askService.beginRun(any(), any())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt())).thenReturn("continue");
        when(askService.runOntology(any(), any(), any(), any())).thenReturn("continue");
        when(askService.runMetadata(any(), any(), any(), eq(true))).thenReturn("continue");
        when(askService.runInsight(any(), any(), any(), any())).thenReturn("answered");

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });

        verify(askService, never()).ask(any(), any());
        verify(askService).runKnowledge(any(), eq(recorder), any(AskFlowState.class), eq(5));
        verify(askService).runOntology(any(), eq(recorder), any(AskFlowState.class), any());
        verify(askService).runMetadata(any(), eq(recorder), any(AskFlowState.class), eq(true));
        verify(askService).runInsight(any(), eq(recorder), any(AskFlowState.class), any());
        verify(askService).finishRun(recorder);
    }

    @Test
    void knowledgeAnsweredShortCircuitsToEnd() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt())).thenReturn("answered");

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });

        verify(askService).runKnowledge(any(), any(), any(), anyInt());
        verify(askService, never()).runOntology(any(), any(), any(), any());
        verify(askService, never()).runInsight(any(), any(), any(), any());
    }

    @Test
    void legacyBuiltinAskStillDelegates() {
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-legacy", "旧问数", 1L, "fv-1", DefaultFlows.legacyBuiltinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });
        verify(askService, times(1)).ask(any(AskRequest.class), any());
    }

    @Test
    void mixedBuiltinAndStagesRejected() {
        FlowGraph mixed = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of()),
                        new FlowNode("ask", "builtin_ask", "整段", Map.of()),
                        new FlowNode("knowledge", "knowledge", "知识库", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("start", "ask", "default"),
                        new FlowEdge("ask", "knowledge", "default"),
                        new FlowEdge("knowledge", "end", "default")));
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-x", "混用", 1L, "fv-x", mixed);
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
                }));
    }

    @Test
    void llmChatEmitsViaAskService() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        AskListener[] outer = new AskListener[1];
        when(askService.beginRun(any(), any())).thenAnswer(invocation -> {
            outer[0] = invocation.getArgument(1);
            outer[0].emit("run", Map.of("runId", "r1", "conversationId", "c1"));
            return recorder;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            outer[0].emit("answer", Map.of("answer", "你好"));
            return null;
        }).when(askService).runLlmChat(any(), any(), any(), any(), any());

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        List<String> events = new ArrayList<>();
        PublishedApp app = new PublishedApp("app-2", "对话", 2L, "fv-2", DefaultFlows.llmChat());
        runner.run(app, new AskRequest("hi", List.of()), (event, data) -> events.add(event));
        assertTrue(events.contains("run"));
        assertTrue(events.contains("answer"));
        verify(askService).finishRun(recorder);
    }

    @Test
    void startNodeMemoryWindowTrimsHistory() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any())).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt())).thenReturn("answered");
        FederationProperties props = new FederationProperties();
        props.getAsk().setHistoryWindowTurns(50);
        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), props);
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of("memoryWindowTurns", 2)),
                        new FlowNode("knowledge", "knowledge", "知识库", Map.of("topK", 5)),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("start", "knowledge", "default"),
                        new FlowEdge("knowledge", "end", "answered"),
                        new FlowEdge("knowledge", "end", "default")));
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
                any());
    }
}
