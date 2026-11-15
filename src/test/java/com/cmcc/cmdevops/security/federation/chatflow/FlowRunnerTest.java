package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.MemoryAskFeedbackStore;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;

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
    void builtinAskDelegatesToAskService() {
        AskService askService = mock(AskService.class);
        FlowRunner runner = new FlowRunner(askService, LlmClient.of(mock(ChatModel.class)), new MemoryAskFeedbackStore());
        PublishedApp app = new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk());
        runner.run(app, new AskRequest("q", List.of()), (event, data) -> {
        });
        verify(askService).ask(any(AskRequest.class), any());
    }

    @Test
    void llmChatEmitsAnswer() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("你好"))
                .build());
        // LlmClient.complete uses model.chat(SystemMessage, UserMessage) - check actual API
        AskFeedbackStore store = new MemoryAskFeedbackStore();
        AskService askService = mock(AskService.class);
        LlmClient llm = mock(LlmClient.class);
        when(llm.model()).thenReturn(model);
        when(llm.complete(any(), any())).thenReturn("你好");
        FlowRunner runner = new FlowRunner(askService, llm, store);
        List<String> events = new ArrayList<>();
        PublishedApp app = new PublishedApp("app-2", "对话", 2L, "fv-2", DefaultFlows.llmChat());
        runner.run(app, new AskRequest("hi", List.of()), (event, data) -> events.add(event));
        assertTrue(events.contains("run"));
        assertTrue(events.contains("answer"));
    }
}
