package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

class NgramTermRetrieverTest {

    @Test
    void retrievesNearNeighborChineseTerms() {
        List<Term> lexicon = List.of(
                new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0),
                new Term("Asset", "concept", "资产", "资产", "资产", "label", 0.9),
                new Term("HighRiskAlert", "defined", "高危告警", "高危告警", "高危告警", "label", 0.95));

        List<Match> hits = NgramTermRetriever.retrieve("高危告警有多少", lexicon, 5);

        assertTrue(hits.stream().anyMatch(m -> m.entity().equals("HighRiskAlert")));
        assertTrue(hits.get(0).quality() > 0);
        assertTrue(hits.stream().allMatch(m -> "ngram".equals(m.source())));
    }

    @Test
    void ignoresUnrelatedShortNoise() {
        List<Term> lexicon = List.of(new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0));
        assertTrue(NgramTermRetriever.retrieve("x", lexicon, 3).isEmpty());
        assertTrue(NgramTermRetriever.retrieve("", lexicon, 3).isEmpty());
        assertFalse(NgramTermRetriever.grams("告警").isEmpty());
    }
}
