package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptSummary;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.EntityHit;

/**
 * 廉价可回答性预检（对齐 NVIDIA Auto Ontology {@code check_answerable} 思路）：
 * 基于本体词表覆盖率，不调用 LLM、不执行 SQL。
 */
public final class AnswerabilityChecker {

    private AnswerabilityChecker() {
    }

    public static Answerability check(BusinessContext context, List<ConceptSummary> concepts, double minConfidence) {
        List<String> matched = context.matches().stream()
                .map(hit -> hit.name() + "(" + hit.kind() + ":" + hit.matchedText() + ")")
                .distinct()
                .toList();
        List<String> gaps = new ArrayList<>(context.unresolved());
        if (OntologyTools.STATUS_NO_MATCH.equals(context.status()) || context.concepts().isEmpty()) {
            gaps.add("问题中的业务对象在本体中没有对应概念");
        }
        if (OntologyTools.STATUS_AMBIGUOUS.equals(context.status())) {
            gaps.addAll(context.warnings());
        }
        double coverage = coverage(context);
        boolean answerable = !context.concepts().isEmpty()
                && !OntologyTools.STATUS_NO_MATCH.equals(context.status())
                && coverage >= minConfidence
                && context.unresolved().stream().noneMatch(u -> u.contains("没有映射表"));
        String suggestion;
        if (answerable) {
            suggestion = "本体覆盖足够，可继续生成 SQL";
        } else if (context.concepts().isEmpty()) {
            List<String> labels = concepts.stream().map(ConceptSummary::labelZh).filter(s -> s != null && !s.isBlank())
                    .limit(6).toList();
            suggestion = labels.isEmpty()
                    ? "请先起草或导入本体概念后再提问"
                    : "可分析的对象包括：" + String.join("、", labels);
        } else {
            suggestion = "可部分覆盖：" + context.concepts().stream()
                    .map(c -> c.labelZh() == null || c.labelZh().isBlank() ? c.name() : c.labelZh())
                    .collect(Collectors.joining("、"))
                    + "；缺口：" + String.join("；", gaps.stream().distinct().limit(5).toList());
        }
        return new Answerability(answerable, round(coverage), context.status(), matched, distinct(gaps), suggestion);
    }

    /** 从问题文本粗提业务词（≥2 字中文或标识符），用于报告未覆盖词面。 */
    public static List<String> uncoveredPhrases(String question, BusinessContext context) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> covered = new LinkedHashSet<>();
        for (EntityHit hit : context.matches()) {
            if (hit.matchedText() != null) {
                covered.add(hit.matchedText().toLowerCase(Locale.ROOT));
            }
        }
        List<String> phrases = new ArrayList<>();
        for (String token : question.split("[\\s,，。？?、；;：:！!（）()\\[\\]{}「」]+")) {
            String t = token.strip();
            if (t.length() < 2) {
                continue;
            }
            if (t.chars().allMatch(ch -> ch < 128 && !Character.isLetterOrDigit(ch))) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            boolean hit = covered.stream().anyMatch(c -> c.contains(lower) || lower.contains(c));
            if (!hit) {
                phrases.add(t);
            }
        }
        return phrases.stream().distinct().limit(8).toList();
    }

    private static double coverage(BusinessContext context) {
        if (context.concepts().isEmpty()) {
            return 0;
        }
        double base = context.confidence();
        if (!context.unresolved().isEmpty()) {
            base = Math.min(base, 0.45);
        }
        if (OntologyTools.STATUS_AMBIGUOUS.equals(context.status())) {
            base = Math.min(base, 0.55);
        }
        return Math.max(0, Math.min(1, base));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static List<String> distinct(List<String> items) {
        return items.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    public record Answerability(
            boolean answerable,
            double coverage,
            String status,
            List<String> matchedTerms,
            List<String> gaps,
            String suggestion) {
    }
}
