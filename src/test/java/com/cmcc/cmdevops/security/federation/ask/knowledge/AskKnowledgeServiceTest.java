package com.cmcc.cmdevops.security.federation.ask.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge.AnswerPair;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService.KnowledgePatch;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

import tools.jackson.databind.json.JsonMapper;

class AskKnowledgeServiceTest {

    private final MemoryAskKnowledgeStore store = new MemoryAskKnowledgeStore();
    private final KnowledgeVectorIndex vectors =
            new KnowledgeVectorIndex(new HashingTermEmbedder(), null, JsonMapper.builder().build());
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AskKnowledgeService service = new AskKnowledgeService(store, vectors, clock::incrementAndGet);

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void draftFromRunCreatesPendingWithoutIndexing() {
        as("alice");
        AskKnowledge draft = service.draftFromRun(answeredRun("run-1", "alice", "最近一天高危告警有多少？"));

        assertEquals(AskKnowledge.PENDING, draft.status());
        assertEquals("最近一天高危告警有多少？", draft.question());
        assertTrue(service.search("最近一天高危告警有多少？", 5).isEmpty());
        assertEquals(draft.id(), service.draftFromRun(answeredRun("run-1", "alice", "最近一天高危告警有多少？")).id());
    }

    @Test
    void approveIndexesQuestionRejectRemovesIt() {
        asAdmin("root");
        AskKnowledge created = service.create(
                "最近一天高危告警有多少？",
                "共 12 条",
                "SELECT count(*) FROM ck81.pangu.alert WHERE level >= 3",
                List.of(Map.of("item", "高危", "source", "ontology")),
                Map.of("sourceRunId", "run-1"));
        assertEquals(AskKnowledge.APPROVED, created.status());
        assertEquals(1, service.search("最近一天高危告警有多少？", 3).size());

        AskKnowledge rejected = service.update(created.id(),
                new KnowledgePatch(null, null, null, null, null, AskKnowledge.REJECTED, "口径过时"));
        assertEquals(AskKnowledge.REJECTED, rejected.status());
        assertTrue(service.search("最近一天高危告警有多少？", 3).isEmpty());
    }

    @Test
    void searchOnlyReturnsApproved() {
        asAdmin("root");
        AskKnowledge pending = store.save(new AskKnowledge(
                0,
                "待审问题",
                new AnswerPair("答案", null, List.of()),
                Map.of(),
                null,
                AskKnowledge.PENDING,
                "alice",
                null,
                null,
                1,
                1));
        vectors.upsert(pending.id(), pending.question());

        AskKnowledge approved = service.create("已通过问题", "答案", null, List.of(), Map.of());
        assertEquals(1, service.search("已通过问题", 5).size());
        assertEquals(approved.id(), service.search("已通过问题", 5).get(0).knowledge().id());
    }

    private static AskRun answeredRun(String id, String user, String question) {
        return new AskRun(
                id,
                "conv-1",
                user,
                question,
                List.of(),
                AskRun.ANSWERED,
                "共 12 条",
                "SELECT 1",
                Map.of("caliber", List.of(Map.of("item", "高危", "source", "ontology"))),
                null,
                10L,
                20L);
    }

    private static void as(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY)));
    }

    private static void asAdmin(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }
}
