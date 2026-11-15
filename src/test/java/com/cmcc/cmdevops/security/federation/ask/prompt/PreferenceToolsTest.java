package com.cmcc.cmdevops.security.federation.ask.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class PreferenceToolsTest {

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void rememberPreferenceWritesActivePersonalPrompt() {
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
        MemoryAskPromptStore store = new MemoryAskPromptStore();
        AskPromptService service = new AskPromptService(store, new AtomicLong(1_000)::incrementAndGet);
        PreferenceTools tools = new PreferenceTools(service);

        String reply = tools.rememberPreference("默认按最近 24 小时统计");

        assertTrue(reply.contains("#"));
        assertTrue(store.activePersonal("alice").isPresent());
        assertTrue(service.compose("基础").contains("默认按最近 24 小时统计"));
    }
}
