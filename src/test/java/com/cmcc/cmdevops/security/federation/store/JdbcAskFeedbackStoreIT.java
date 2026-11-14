package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskSuggestion;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAskFeedbackStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    private static FederalDataSource dataSource;
    private static JdbcAskFeedbackStore store;

    @BeforeAll
    static void openStore() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());

        dataSource = new FederalDataSource(properties, JsonMapper.builder().build());
        store = new JdbcAskFeedbackStore(dataSource, JsonMapper.builder().build());
    }

    @BeforeEach
    void cleanTables() {
        dataSource.jdbc().update("DELETE FROM fed_ask_suggestion");
        dataSource.jdbc().update("DELETE FROM fed_ask_run");
    }

    @Test
    void runRoundTripKeepsHistoryAndEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("tables", List.of("ck81.pangu.alert"));
        evidence.put("rows", List.of(List.of("web-01", 5)));
        evidence.put("caliber", List.of(Map.of("item", "高危告警", "source", "ontology", "detail", "level >= 3")));
        AskRun run = new AskRun("run-1", "conv-1", "alice", "最近一天高危告警最多的资产",
                List.of(new Turn("user", "高危告警最多的资产"), new Turn("assistant", "要看哪个时间范围？")),
                AskRun.ANSWERED, "web-01 最多，5 条", "SELECT 1", evidence, null, 1_700_000_000_000L, 1_200);

        store.saveRun(run);

        assertEquals(run, store.findRun("run-1").orElseThrow());
        assertTrue(store.findRun("missing").isEmpty());
    }

    @Test
    void failedRunKeepsErrorWithoutAnswer() {
        store.saveRun(new AskRun("run-err", "run-err", "alice", "q", List.of(), AskRun.ERROR, null, null,
                Map.of(), "模型超时", 1_000, 10));

        AskRun read = store.findRun("run-err").orElseThrow();
        assertEquals("模型超时", read.error());
        assertNull(read.answer());
        assertNull(read.sql());
        assertTrue(read.history().isEmpty());
    }

    @Test
    void ratingIsUpsertedPerUserAndCleared() {
        store.saveRun(run("run-1", "alice"));

        assertTrue(store.ratingOf("run-1", "alice").isEmpty());
        store.rate("run-1", "alice", "up", 1_000);
        store.rate("run-1", "root", "down", 1_001);
        store.rate("run-1", "alice", "down", 1_002);

        assertEquals("down", store.ratingOf("run-1", "alice").orElseThrow());
        assertEquals("down", store.ratingOf("run-1", "root").orElseThrow());
        assertEquals(2, dataSource.jdbc().queryForObject("SELECT COUNT(*) FROM fed_ask_feedback", Integer.class));

        store.rate("run-1", "alice", null, 1_003);
        assertTrue(store.ratingOf("run-1", "alice").isEmpty());
        assertEquals("down", store.ratingOf("run-1", "root").orElseThrow());
    }

    @Test
    void suggestionLifecycleAndFilters() {
        store.saveRun(run("run-1", "alice"));

        AskSuggestion linked = store.addSuggestion("run-1", "alice", "高危应该按 level >= 4 算", 1_000);
        AskSuggestion loose = store.addSuggestion(null, "bob", "希望能导出结果", 2_000);

        assertEquals("问题 run-1", linked.runQuestion());
        assertEquals(AskSuggestion.OPEN, linked.status());
        assertNull(loose.runId());
        assertNull(loose.runQuestion());
        assertEquals(List.of(loose.id(), linked.id()), ids(store.listSuggestions(null, null)));
        assertEquals(List.of(linked.id()), ids(store.listSuggestions("alice", null)));

        AskSuggestion adopted = new AskSuggestion(linked.id(), linked.runId(), linked.runQuestion(),
                linked.userName(), linked.content(), AskSuggestion.ADOPTED, "ontology", "已改派生概念", "root",
                linked.createdAt(), 3_000);
        store.updateSuggestion(adopted);

        assertEquals(adopted, store.findSuggestion(linked.id()).orElseThrow());
        assertEquals(List.of(linked.id()), ids(store.listSuggestions(null, AskSuggestion.ADOPTED)));
        assertEquals(List.of(loose.id()), ids(store.listSuggestions(null, AskSuggestion.OPEN)));
        assertTrue(store.listSuggestions("bob", AskSuggestion.ADOPTED).isEmpty());

        store.deleteSuggestion(loose.id());
        assertTrue(store.findSuggestion(loose.id()).isEmpty());
    }

    @Test
    void deletingRunDropsRatingsAndUnlinksSuggestions() {
        store.saveRun(run("run-1", "alice"));
        store.rate("run-1", "alice", "up", 1_000);
        long id = store.addSuggestion("run-1", "alice", "建议", 1_000).id();

        dataSource.jdbc().update("DELETE FROM fed_ask_run WHERE id = ?", "run-1");

        assertTrue(store.ratingOf("run-1", "alice").isEmpty());
        AskSuggestion kept = store.findSuggestion(id).orElseThrow();
        assertNull(kept.runId());
        assertNull(kept.runQuestion());
    }

    private static AskRun run(String id, String user) {
        return new AskRun(id, id, user, "问题 " + id, List.of(), AskRun.ANSWERED, "回答", "SELECT 1", Map.of(),
                null, 1_000, 5);
    }

    private static List<Long> ids(List<AskSuggestion> suggestions) {
        return suggestions.stream().map(AskSuggestion::id).toList();
    }
}
