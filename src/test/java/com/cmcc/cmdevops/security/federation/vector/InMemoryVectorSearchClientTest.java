package com.cmcc.cmdevops.security.federation.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryVectorSearchClientTest {

    private final InMemoryVectorSearchClient client = new InMemoryVectorSearchClient();

    @Test
    void prefilterKeepsOnlyMatchingCategory() {
        List<VectorHit> hits = client.search("milvus.products", "embedding", List.of(1.0, 0.0, 0.0), 10, "category == \"phone\"");
        assertEquals(3, hits.size());
        assertTrue(hits.get(0).score() > 0.9);
        assertEquals(1L, hits.get(0).id());
    }

    @Test
    void honorTopK() {
        List<VectorHit> hits = client.search("milvus.products", "embedding", List.of(1.0, 0.0, 0.0), 1, null);
        assertEquals(1, hits.size());
    }
}
