package com.cmcc.cmdevops.security.federation.ask.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;

import tools.jackson.databind.ObjectMapper;

class KnowledgeVectorIndexTest {

    @Test
    void memoryModeIndexesAndSearchesWithoutMilvus() {
        KnowledgeVectorIndex index =
                new KnowledgeVectorIndex(new HashingTermEmbedder(32), null, new ObjectMapper());
        assertFalse(index.milvusEnabled());
        AskKnowledge item = sample(1L, "最近一天高危告警有多少？", "共 12 条");
        index.upsert(item);
        List<KnowledgeVectorIndex.Hit> hits = index.search("最近一天高危告警有多少？", 3);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).answer().contains("答："));
        assertEquals("2026", hits.get(0).year());
        index.delete(1L);
        assertTrue(index.search("最近一天高危告警有多少？", 3).isEmpty());
    }

    @Test
    void milvusModeDoesNotFallBackToMemoryOnSearchFailure() {
        RestClient dead = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        KnowledgeVectorIndex index =
                new KnowledgeVectorIndex(new HashingTermEmbedder(32), dead, new ObjectMapper());
        assertTrue(index.milvusEnabled());
        assertThrows(RuntimeException.class, () -> index.upsert(sample(9L, "高危告警数量", "若干")));
        assertTrue(index.search("高危告警数量", 5).isEmpty());
    }

    @Test
    void sparseOfIsNonEmpty() {
        assertFalse(KnowledgeVectorIndex.sparseOf("告警").isEmpty());
        assertEquals(1.0f, KnowledgeVectorIndex.sparseOf("").get("0"));
    }

    private static AskKnowledge sample(long id, String question, String answerBody) {
        return new AskKnowledge(
                id,
                question,
                new AskKnowledge.AnswerPair(KnowledgeQaPair.format(question, answerBody), null, List.of()),
                Map.of(),
                null,
                AskKnowledge.KIND_SMART_ASK,
                AskKnowledge.APPROVED,
                "tester",
                null,
                null,
                "2026",
                "1.0.0",
                1L,
                1L);
    }
}
