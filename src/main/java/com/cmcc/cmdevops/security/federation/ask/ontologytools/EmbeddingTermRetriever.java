package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.nio.file.Path;
import java.util.List;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 基于 {@link TermEmbedder} + {@link OntologyEmbeddingIndex} 的术语向量召回。
 */
public final class EmbeddingTermRetriever {

    private static final TermEmbedder DEFAULT = new HashingTermEmbedder();

    private EmbeddingTermRetriever() {
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, int limit) {
        return retrieve(question, lexicon, DEFAULT, null, limit);
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, TermEmbedder embedder, int limit) {
        return retrieve(question, lexicon, embedder, null, limit);
    }

    public static List<Match> retrieve(
            String question, List<Term> lexicon, TermEmbedder embedder, Path cacheDir, int limit) {
        if (question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        OntologyEmbeddingIndex index = OntologyEmbeddingIndex.build(lexicon, embedder, cacheDir);
        return index.retrieve(question, embedder, limit);
    }
}
