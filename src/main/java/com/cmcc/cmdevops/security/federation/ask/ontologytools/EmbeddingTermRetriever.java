package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 基于本地哈希嵌入的术语向量召回。字面命中不足时使用，可与 {@link NgramTermRetriever} 并联。
 */
public final class EmbeddingTermRetriever {

    private static final double MIN_SIM = 0.42;
    private static final HashingTermEmbedder DEFAULT = new HashingTermEmbedder();

    private EmbeddingTermRetriever() {
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, int limit) {
        return retrieve(question, lexicon, DEFAULT, limit);
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, HashingTermEmbedder embedder, int limit) {
        if (question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        float[] query = embedder.embed(question);
        Map<String, Match> best = new LinkedHashMap<>();
        for (Term term : lexicon) {
            double sim = HashingTermEmbedder.cosine(query, embedder.embed(term.text()));
            if (sim < MIN_SIM) {
                continue;
            }
            double quality = term.quality() * (0.4 + 0.5 * sim);
            Match candidate = new Match(term.entity(), term.kind(), term.original(), "embed", quality, "embed");
            best.merge(term.entity(), candidate, (a, b) -> a.quality() >= b.quality() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Match::quality).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
