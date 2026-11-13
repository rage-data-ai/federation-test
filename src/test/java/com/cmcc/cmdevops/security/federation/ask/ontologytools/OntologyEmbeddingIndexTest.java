package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

class OntologyEmbeddingIndexTest {

    @BeforeEach
    void clearCache() {
        OntologyEmbeddingIndex.clearMemoryCache();
    }

    @Test
    void persistsAndReusesCache(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        TermEmbedder counting = new TermEmbedder() {
            private final HashingTermEmbedder delegate = new HashingTermEmbedder(64);

            @Override
            public String name() {
                return "count";
            }

            @Override
            public float[] embed(String text) {
                calls.incrementAndGet();
                return delegate.embed(text);
            }
        };
        List<Term> lexicon = List.of(
                new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0),
                new Term("HighRiskAlert", "defined", "高危告警", "高危告警", "高危告警", "label", 0.95));

        OntologyEmbeddingIndex first = OntologyEmbeddingIndex.build(lexicon, counting, dir);
        int afterBuild = calls.get();
        assertTrue(afterBuild >= 2);
        OntologyEmbeddingIndex.clearMemoryCache();
        OntologyEmbeddingIndex second = OntologyEmbeddingIndex.build(lexicon, counting, dir);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(afterBuild, calls.get(), "disk cache should avoid re-embedding lexicon");

        List<Match> hits = second.retrieve("高危告警有多少", counting, 5);
        assertTrue(hits.stream().anyMatch(m -> m.entity().equals("HighRiskAlert")));
    }
}
