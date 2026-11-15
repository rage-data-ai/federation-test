package com.cmcc.cmdevops.security.federation.chatflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class AppServiceTest {

    private MemoryChatflowStore store;
    private AppService apps;
    private FlowService flows;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
        store = new MemoryChatflowStore();
        apps = new AppService(store);
        flows = new FlowService(store);
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void defaultAppIsPublished() {
        PublishedApp published = apps.resolvePublished(null);
        assertEquals(DefaultFlows.DEFAULT_APP_ID, published.appId());
        assertEquals("builtin_ask", published.graph().node("ask").type());
    }

    @Test
    void createFromTemplateAndPublish() {
        App draft = apps.createFromTemplate("值班助手", "仅对话", DefaultFlows.TEMPLATE_LLM_CHAT);
        assertEquals(App.DRAFT, draft.status());
        assertThrows(ResponseStatusException.class, () -> apps.resolvePublished(draft.id()));

        AppRelease release = apps.publish(draft.id(), null);
        assertTrue(release.id() > 0);
        PublishedApp published = apps.resolvePublished(draft.id());
        assertEquals("llm_chat", published.graph().node("chat").type());
        assertEquals(release.id(), published.releaseId());
    }

    @Test
    void updateFlowThenPublishPinsNewGraph() {
        App draft = apps.createFromTemplate("定制", "", DefaultFlows.TEMPLATE_LLM_CHAT);
        var flow = flows.get(draft.flowId());
        FlowGraph graph = DefaultFlows.builtinAsk();
        flows.update(flow.id(), "改成问数", graph);
        AppRelease release = apps.publish(draft.id(), null);
        assertNotNull(release.graph().node("ask"));
        assertEquals("builtin_ask", apps.resolvePublished(draft.id()).graph().node("ask").type());
    }
}
