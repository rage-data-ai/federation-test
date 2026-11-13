package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

class EmbeddingTermRetrieverTest {

    @Test
    void selfSimilarityIsNearOne() {
        HashingTermEmbedder embedder = new HashingTermEmbedder();
        float[] a = embedder.embed("高危告警");
        assertTrue(HashingTermEmbedder.cosine(a, a) > 0.99);
        assertTrue(HashingTermEmbedder.cosine(a, embedder.embed("高危告警数量")) > 0.5);
    }

    @Test
    void retrievesNearNeighborTerms() {
        List<Term> lexicon = List.of(
                new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0),
                new Term("Asset", "concept", "资产", "资产", "资产", "label", 0.9),
                new Term("HighRiskAlert", "defined", "高危告警", "高危告警", "高危告警", "label", 0.95));

        List<Match> hits = EmbeddingTermRetriever.retrieve("高危告警有多少", lexicon, 5);

        assertTrue(hits.stream().anyMatch(m -> m.entity().equals("HighRiskAlert")));
        assertTrue(hits.get(0).source().startsWith("embed:"));
    }
}
