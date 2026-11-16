package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
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

import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.MemoryAskFeedbackStore;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

import dev.langchain4j.model.chat.ChatModel;

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
    void stageGraphCollapsesToSingleAsk() {
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(
                askService, LlmClient.of(mock(ChatModel.class)), new MemoryAskFeedbackStore(), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });
        verify(askService, times(1)).ask(any(AskRequest.class), any());
    }

    @Test
    void legacyBuiltinAskStillDelegates() {
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(
                askService, LlmClient.of(mock(ChatModel.class)), new MemoryAskFeedbackStore(), new FederationProperties());
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
        FlowRunner runner =
                new FlowRunner(askService, mock(LlmClient.class), new MemoryAskFeedbackStore(), new FederationProperties());
        PublishedApp app = new PublishedApp("app-x", "混用", 1L, "fv-x", mixed);
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
                }));
    }

    @Test
    void llmChatEmitsAnswer() {
        ChatModel model = mock(ChatModel.class);
        AskFeedbackStore store = new MemoryAskFeedbackStore();
        AskService askService = mock(AskService.class);
        LlmClient llm = mock(LlmClient.class);
        when(llm.model()).thenReturn(model);
        when(llm.complete(any(), any())).thenReturn("你好");
        FlowRunner runner = new FlowRunner(askService, llm, store, new FederationProperties());
        List<String> events = new ArrayList<>();
        PublishedApp app = new PublishedApp("app-2", "对话", 2L, "fv-2", DefaultFlows.llmChat());
        runner.run(app, new AskRequest("hi", List.of()), (event, data) -> events.add(event));
        assertTrue(events.contains("run"));
        assertTrue(events.contains("answer"));
    }

    @Test
    void startNodeMemoryWindowTrimsHistory() {
        AskService askService = mock(AskService.class);
        FederationProperties props = new FederationProperties();
        props.getAsk().setHistoryWindowTurns(50);
        FlowRunner runner = new FlowRunner(askService, mock(LlmClient.class), new MemoryAskFeedbackStore(), props);
        FlowGraph graph = FlowGraph.of(
                "start",
                List.of(
                        new FlowNode("start", "start", "开始", Map.of("memoryWindowTurns", 2)),
                        new FlowNode("knowledge", "knowledge", "知识库", Map.of("topK", 5)),
                        new FlowNode("ontology", "ontology", "本体", Map.of()),
                        new FlowNode("metadata", "metadata", "元数据", Map.of()),
                        new FlowNode("insight", "insight", "SQL洞察", Map.of()),
                        new FlowNode("end", "end", "结束", Map.of())),
                List.of(
                        new FlowEdge("start", "knowledge", "default"),
                        new FlowEdge("knowledge", "ontology", "continue"),
                        new FlowEdge("ontology", "metadata", "continue"),
                        new FlowEdge("metadata", "insight", "continue"),
                        new FlowEdge("insight", "end", "default")));
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
        verify(askService).ask(
                argThat(req -> {
                    assertEquals(2, req.history().size());
                    assertEquals("q3", req.history().get(0).content());
                    assertEquals("a3", req.history().get(1).content());
                    return true;
                }),
                any());
    }
}
