package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

class CompositeOntologyEmbeddingStoreTest {

    @BeforeEach
    void clearCache() {
        OntologyEmbeddingIndex.clearMemoryCache();
    }

    @Test
    void loadsFromPrimaryAndBackfillsSecondary() {
        MapStore primary = new MapStore();
        MapStore secondary = new MapStore();
        AtomicInteger calls = new AtomicInteger();
        TermEmbedder counting = countingEmbedder(calls);
        List<Term> lexicon = List.of(new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0));

        OntologyEmbeddingIndex built =
                OntologyEmbeddingIndex.build(lexicon, counting, new CompositeOntologyEmbeddingStore(List.of(primary, secondary)));
        assertTrue(calls.get() >= 1);
        assertNotNull(primary.load(built.fingerprint(), built.embedderName()));
        assertNotNull(secondary.load(built.fingerprint(), built.embedderName()));

        OntologyEmbeddingIndex.clearMemoryCache();
        primary.clear();
        int afterClear = calls.get();
        OntologyEmbeddingIndex reused =
                OntologyEmbeddingIndex.build(lexicon, counting, new CompositeOntologyEmbeddingStore(List.of(primary, secondary)));
        assertEquals(built.fingerprint(), reused.fingerprint());
        assertEquals(afterClear, calls.get(), "secondary hit should avoid re-embedding");
        assertNotNull(primary.load(built.fingerprint(), built.embedderName()), "primary should be backfilled");
    }

    @Test
    void encodeDecodeRoundTrip() {
        List<Term> lexicon = List.of(
                new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0),
                new Term("HighRiskAlert", "defined", "高危告警", "高危告警", "高危告警", "label", 0.95));
        OntologyEmbeddingIndex index = OntologyEmbeddingIndex.build(lexicon, new HashingTermEmbedder(64), (OntologyEmbeddingStore) null);
        byte[] payload = OntologyEmbeddingIndex.encode(index);
        OntologyEmbeddingIndex decoded =
                OntologyEmbeddingIndex.decode(payload, index.fingerprint(), index.embedderName());
        assertNotNull(decoded);
        assertEquals(index.fingerprint(), decoded.fingerprint());
        assertEquals(index.size(), decoded.size());
    }

    private static TermEmbedder countingEmbedder(AtomicInteger calls) {
        return new TermEmbedder() {
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
    }

    private static final class MapStore implements OntologyEmbeddingStore {
        private final Map<String, byte[]> rows = new LinkedHashMap<>();

        @Override
        public OntologyEmbeddingIndex load(String fingerprint, String embedderName) {
            byte[] payload = rows.get(key(fingerprint, embedderName));
            return OntologyEmbeddingIndex.decode(payload, fingerprint, embedderName);
        }

        @Override
        public void save(OntologyEmbeddingIndex index) {
            rows.put(key(index.fingerprint(), index.embedderName()), OntologyEmbeddingIndex.encode(index));
        }

        void clear() {
            rows.clear();
        }

        private static String key(String fingerprint, String embedderName) {
            return fingerprint + "|" + embedderName;
        }
    }
}
