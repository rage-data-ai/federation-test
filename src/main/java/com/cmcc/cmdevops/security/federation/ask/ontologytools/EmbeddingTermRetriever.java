package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 基于 {@link TermEmbedder} 的术语向量召回。字面命中为空时使用，可与 {@link NgramTermRetriever} 并联。
 */
public final class EmbeddingTermRetriever {

    private static final double MIN_SIM = 0.42;
    private static final TermEmbedder DEFAULT = new HashingTermEmbedder();

    private EmbeddingTermRetriever() {
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, int limit) {
        return retrieve(question, lexicon, DEFAULT, limit);
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, TermEmbedder embedder, int limit) {
        if (question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        TermEmbedder engine = embedder == null ? DEFAULT : embedder;
        float[] query = engine.embed(question);
        Map<String, Match> best = new LinkedHashMap<>();
        String source = "embed:" + engine.name();
        for (Term term : lexicon) {
            double sim = HashingTermEmbedder.cosine(query, engine.embed(term.text()));
            if (sim < MIN_SIM) {
                continue;
            }
            double quality = term.quality() * (0.4 + 0.5 * sim);
            Match candidate = new Match(term.entity(), term.kind(), term.original(), source, quality, source);
            best.merge(term.entity(), candidate, (a, b) -> a.quality() >= b.quality() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Match::quality).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
