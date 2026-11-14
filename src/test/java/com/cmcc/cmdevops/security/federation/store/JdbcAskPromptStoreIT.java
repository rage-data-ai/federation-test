package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.ask.prompt.AskPrompt;
import com.cmcc.cmdevops.security.federation.ask.prompt.JdbcAskPromptStore;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAskPromptStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    private static FederalDataSource dataSource;
    private static JdbcAskPromptStore store;

    @BeforeAll
    static void openStore() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());
        dataSource = new FederalDataSource(properties, JsonMapper.builder().build());
        store = new JdbcAskPromptStore(dataSource);
    }

    @BeforeEach
    void clean() {
        dataSource.jdbc().update("DELETE FROM fed_ask_prompt");
    }

    @Test
    void roundTripActiveLookupAndDelete() {
        AskPrompt global = store.save(new AskPrompt(
                0, AskPrompt.GLOBAL, null, "全局补充", AskPrompt.ACTIVE, AskPrompt.HUMAN,
                1, "root", "root", 1_000, 1_000));
        AskPrompt personal = store.save(new AskPrompt(
                0, AskPrompt.PERSONAL, "alice", "个人补充", AskPrompt.ACTIVE, AskPrompt.HUMAN,
                1, "alice", "alice", 1_001, 1_001));

        assertTrue(global.id() > 0);
        assertEquals(global, store.find(global.id()).orElseThrow());
        assertEquals("全局补充", store.activeGlobal().orElseThrow().content());
        assertEquals("个人补充", store.activePersonal("alice").orElseThrow().content());
        assertTrue(store.activePersonal("bob").isEmpty());
        assertEquals(2, store.list(null, null).size());
        assertEquals(1, store.list(AskPrompt.PERSONAL, "alice").size());

        store.save(new AskPrompt(
                global.id(), global.scope(), global.owner(), "全局 v2", AskPrompt.ACTIVE, global.source(),
                2, global.createdBy(), "root", global.createdAt(), 2_000));
        assertEquals("全局 v2", store.activeGlobal().orElseThrow().content());

        store.delete(personal.id());
        assertTrue(store.find(personal.id()).isEmpty());
    }
}
