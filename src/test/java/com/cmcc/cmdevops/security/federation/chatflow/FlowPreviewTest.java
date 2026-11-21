package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskStageResult;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class FlowPreviewTest {

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void previewEmitsNodeLifecycleAndDoesNotPersistRun() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), eq(true))).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", List.of())));
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("canAnswer", false)));
        when(askService.runOntology(any(), any(), any(), any()))
                .thenReturn(AskStageResult.of("continue", Map.of("context", Map.of())));
        when(askService.runMetadata(any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("error", Map.of("tables", 0)));

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        PublishedApp app = new PublishedApp("app-1", "预览", 0L, "preview", DefaultFlows.builtinAsk());
        List<String> events = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        runner.runPreview(app, new AskRequest("q", List.of()), (event, data) -> {
            events.add(event);
            payloads.add(data);
        });

        assertTrue(events.contains("node_start"));
        assertTrue(events.contains("node_finish"));
        assertTrue(events.contains("vars"));
        long starts = events.stream().filter("node_start"::equals).count();
        long finishes = events.stream().filter("node_finish"::equals).count();
        assertEquals(starts, finishes);
        assertTrue(starts >= 3);

        Object firstStart = payloads.get(events.indexOf("node_start"));
        assertTrue(firstStart instanceof Map<?, ?>);
        assertEquals("start", ((Map<?, ?>) firstStart).get("nodeId"));

        verify(askService).beginRun(any(), any(), eq(true));
        verify(askService).discardRun(recorder);
        verify(askService, never()).finishRun(any());
        verify(askService).runOntology(any(), eq(recorder), any(AskFlowState.class), any());
        verify(askService).runMetadata(any(), eq(recorder), any(AskFlowState.class), eq(true));
        verify(askService, never()).runInsight(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void productionRunStillPersists() {
        AskService askService = mock(AskService.class);
        AskRunRecorder recorder = mock(AskRunRecorder.class);
        when(askService.beginRun(any(), any(), eq(false))).thenReturn(recorder);
        when(askService.runKnowledge(any(), any(), any(), anyInt()))
                .thenReturn(AskStageResult.of("default", Map.of("result", List.of())));
        when(askService.runLlmChat(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(AskStageResult.of("default", Map.of("canAnswer", true, "answer", "hit")));

        FlowRunner runner = new FlowRunner(askService, mock(KnowledgeTools.class), new FederationProperties());
        runner.run(
                new PublishedApp("app-1", "问数", 1L, "fv-1", DefaultFlows.builtinAsk()),
                new AskRequest("q", List.of()),
                (event, data) -> {
                });

        verify(askService).beginRun(any(), any(), eq(false));
        verify(askService).finishRun(recorder);
        verify(askService, never()).discardRun(any());
    }
}
