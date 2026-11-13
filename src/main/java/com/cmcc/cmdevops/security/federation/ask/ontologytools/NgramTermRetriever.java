package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 基于字符 n-gram 的轻量术语召回（无外部 embedding / 向量库）。
 * 在词表字面命中不足时补充候选，对齐 Auto Ontology「embedding 术语检索」的工程意图。
 */
public final class NgramTermRetriever {

    private static final double MIN_SIM = 0.52;

    private NgramTermRetriever() {
    }

    public static List<Match> retrieve(String question, List<Term> lexicon, int limit) {
        if (question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        Map<String, Double> query = grams(question.toLowerCase(Locale.ROOT));
        if (query.isEmpty()) {
            return List.of();
        }
        Map<String, Match> best = new LinkedHashMap<>();
        for (Term term : lexicon) {
            double sim = cosine(query, grams(term.text()));
            if (sim < MIN_SIM) {
                continue;
            }
            double quality = term.quality() * (0.35 + 0.45 * sim);
            Match candidate = new Match(term.entity(), term.kind(), term.original(), "ngram", quality, "ngram");
            best.merge(term.entity(), candidate, (a, b) -> a.quality() >= b.quality() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Match::quality).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public static Map<String, Double> grams(String text) {
        Map<String, Double> bag = new HashMap<>();
        String cleaned = text.replaceAll("\\s+", "");
        if (cleaned.length() < 2) {
            return bag;
        }
        for (int i = 0; i < cleaned.length() - 1; i++) {
            String g = cleaned.substring(i, i + 2);
            bag.merge(g, 1.0, Double::sum);
        }
        if (cleaned.length() >= 3) {
            for (int i = 0; i < cleaned.length() - 2; i++) {
                String g = cleaned.substring(i, i + 3);
                bag.merge(g, 0.5, Double::sum);
            }
        }
        return bag;
    }

    public static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        if (dot == 0) {
            return 0;
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(Map<String, Double> bag) {
        double sum = 0;
        for (double v : bag.values()) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }
}
