package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge.AnswerPair;
import com.cmcc.cmdevops.security.federation.ask.knowledge.JdbcAskKnowledgeStore;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAskKnowledgeStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    private static FederalDataSource dataSource;
    private static JdbcAskKnowledgeStore store;
    private static JdbcAskFeedbackStore feedback;

    @BeforeAll
    static void openStore() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());
        var json = JsonMapper.builder().build();
        dataSource = new FederalDataSource(properties, json);
        feedback = new JdbcAskFeedbackStore(dataSource, json);
        store = new JdbcAskKnowledgeStore(dataSource, json);
    }

    @BeforeEach
    void clean() {
        dataSource.jdbc().update("DELETE FROM fed_ask_knowledge");
        dataSource.jdbc().update("DELETE FROM fed_ask_run");
    }

    @Test
    void roundTripAndFindByIds() {
        feedback.saveRun(new AskRun("run-1", "conv", "alice", "q", List.of(), AskRun.ANSWERED,
                "a", "SELECT 1", Map.of(), null, 1_000, 5));
        AskKnowledge saved = store.save(new AskKnowledge(
                0,
                "最近一天高危告警有多少？",
                new AnswerPair("12", "SELECT 1", List.of(Map.of("item", "高危"))),
                Map.of("sourceRunId", "run-1"),
                "run-1",
                AskKnowledge.PENDING,
                "alice",
                null,
                null,
                1_000,
                1_000));

        assertTrue(saved.id() > 0);
        assertEquals(saved, store.find(saved.id()).orElseThrow());
        assertEquals(List.of(saved), store.list(AskKnowledge.PENDING));
        assertEquals(List.of(saved), store.findByIds(List.of(saved.id(), 999L)));

        AskKnowledge updated = store.save(new AskKnowledge(
                saved.id(),
                saved.question(),
                saved.answerPair(),
                saved.evidence(),
                saved.sourceRunId(),
                AskKnowledge.APPROVED,
                saved.createdBy(),
                "root",
                "ok",
                saved.createdAt(),
                2_000));
        assertEquals(AskKnowledge.APPROVED, store.find(saved.id()).orElseThrow().status());
        assertEquals("root", updated.reviewedBy());

        store.delete(saved.id());
        assertTrue(store.find(saved.id()).isEmpty());
    }
}
