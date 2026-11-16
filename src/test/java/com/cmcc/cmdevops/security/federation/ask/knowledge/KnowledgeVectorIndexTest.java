package com.cmcc.cmdevops.security.federation.ask.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.ObjectMapper;

class KnowledgeVectorIndexTest {

    @Test
    void memoryModeIndexesAndSearchesWithoutMilvus() {
        KnowledgeVectorIndex index =
                new KnowledgeVectorIndex(new HashingTermEmbedder(32), null, new ObjectMapper());
        assertFalse(index.milvusEnabled());
        index.upsert(1L, "最近一天高危告警有多少？");
        assertEquals(1, index.search("最近一天高危告警有多少？", 3).size());
        index.delete(1L);
        assertTrue(index.search("最近一天高危告警有多少？", 3).isEmpty());
    }

    @Test
    void milvusModeDoesNotFallBackToMemoryOnSearchFailure() {
        RestClient dead = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        KnowledgeVectorIndex index =
                new KnowledgeVectorIndex(new HashingTermEmbedder(32), dead, new ObjectMapper());
        assertTrue(index.milvusEnabled());
        assertThrows(RuntimeException.class, () -> index.upsert(9L, "高危告警数量"));
        assertTrue(index.search("高危告警数量", 5).isEmpty());
    }

    @Test
    void milvusRequiresEmbedEndpoint() {
        FederationProperties props = new FederationProperties();
        props.getVector().setMilvusUri("http://127.0.0.1:19530");
        props.getAsk().setEmbedEndpoint("");
        assertThrows(IllegalStateException.class, () -> KnowledgeVectorIndex.knowledgeEmbedder(props, true));
    }
}
