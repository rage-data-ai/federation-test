package com.cmcc.cmdevops.security.federation.ask.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService.PromptPatch;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class AskPromptServiceTest {

    private final MemoryAskPromptStore store = new MemoryAskPromptStore();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AskPromptService service = new AskPromptService(store, clock::incrementAndGet);

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void composeAppendsActiveGlobalAndPersonal() {
        asAdmin("root");
        AskPrompt global = service.create(AskPrompt.GLOBAL, "全局：默认最近 7 天", AskPrompt.HUMAN);
        service.activate(global.id());

        as("alice");
        service.create(AskPrompt.PERSONAL, "个人：回答尽量短", AskPrompt.HUMAN);

        String composed = service.compose("基础规则");
        assertTrue(composed.startsWith("基础规则"));
        assertTrue(composed.contains("全局：默认最近 7 天"));
        assertTrue(composed.contains("个人：回答尽量短"));
    }

    @Test
    void activateArchivesPreviousActive() {
        asAdmin("root");
        AskPrompt first = service.create(AskPrompt.GLOBAL, "v1", AskPrompt.HUMAN);
        service.activate(first.id());
        AskPrompt second = service.create(AskPrompt.GLOBAL, "v2", AskPrompt.HUMAN);
        service.activate(second.id());

        assertEquals(AskPrompt.ARCHIVED, store.find(first.id()).orElseThrow().status());
        assertEquals(AskPrompt.ACTIVE, store.find(second.id()).orElseThrow().status());
        assertEquals("v2", store.activeGlobal().orElseThrow().content());
    }

    @Test
    void personalCreateAutoActivates() {
        as("alice");
        AskPrompt saved = service.create(AskPrompt.PERSONAL, "偏好 A", AskPrompt.HUMAN);
        assertEquals(AskPrompt.ACTIVE, saved.status());
        assertEquals("偏好 A", store.activePersonal("alice").orElseThrow().content());

        service.update(saved.id(), new PromptPatch(null, AskPrompt.ARCHIVED));
        assertFalse(store.activePersonal("alice").isPresent());
    }

    private static void as(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY)));
    }

    private static void asAdmin(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }
}
