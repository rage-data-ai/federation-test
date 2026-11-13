package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.DefinedView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.EntityHit;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.JoinPath;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;

/**
 * Builds {@link BusinessContext} from a question and/or selected entities:
 * lexicon matching, seed extraction, path linking, and confidence.
 */
public final class BusinessContextBuilder {

    private final OntologyRepository repository;
    private final OntologyPathFinder pathFinder;
    private final OntologyEntityViews views;

    public BusinessContextBuilder(OntologyRepository repository, OntologyPathFinder pathFinder, OntologyEntityViews views) {
        this.repository = repository;
        this.pathFinder = pathFinder;
        this.views = views;
    }

    public BusinessContext businessContext(String question, Set<String> extraEntities) {
        return build(question, extraEntities, false);
    }

    /** 只用多轮工具 Agent 选定的实体构造上下文，不再做词面匹配（歧义已由 Agent 消解）。 */
    public BusinessContext contextOf(Set<String> entities) {
        return build(null, entities, true);
    }

    private BusinessContext build(String question, Set<String> extraEntities, boolean exclusive) {
        Snapshot snapshot = repository.snapshot();
        OntologyIndex index = new OntologyIndex(snapshot);
        List<String> warnings = new ArrayList<>();
        List<Match> matches = exclusive ? new ArrayList<>()
                : match(question == null ? "" : question, lexicon(snapshot), warnings);
        for (String entity : extraEntities) {
            String kind = index.kindOf(entity);
            if (kind == null) {
                warnings.add("本体中没有实体 " + entity);
            } else if (matches.stream().noneMatch(m -> m.entity().equals(entity))) {
                matches.add(new Match(entity, kind, entity, "agent", 0.8, "agent:" + entity));
            }
        }
        matches = lockCodeConcepts(index, matches, warnings);

        Set<String> seeds = new LinkedHashSet<>();
        Set<String> attributes = new LinkedHashSet<>();
        Set<String> relations = new LinkedHashSet<>();
        Set<String> defined = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        Map<String, Set<String>> seedSpans = new HashMap<>();
        for (Match match : matches) {
            Set<String> matchSeeds = new LinkedHashSet<>();
            switch (match.kind()) {
                case "concept" -> matchSeeds.add(match.entity());
                case "attribute" -> {
                    attributes.add(match.entity());
                    addSeed(matchSeeds, index.attributes.get(match.entity()).domain());
                }
                case "relation" -> {
                    Relation relation = index.relations.get(match.entity());
                    relations.add(relation.name());
                    addSeed(matchSeeds, relation.domain());
                    addSeed(matchSeeds, relation.range());
                }
                case "defined" -> {
                    defined.add(match.entity());
                    addSeed(matchSeeds, index.defined.get(match.entity()).base());
                }
                case "code" -> {
                    Code code = index.codes.get(match.entity());
                    codes.add(code.name());
                    Attribute owner = index.codeOwner(code.codeList());
                    if (owner == null) {
                        warnings.add("码值 " + code.name() + " 的集合 " + code.codeList() + " 没有挂在任何属性上，无法确定概念");
                    } else {
                        attributes.add(owner.name());
                        addSeed(matchSeeds, owner.domain());
                    }
                }
                default -> {
                }
            }
            seeds.addAll(matchSeeds);
            matchSeeds.forEach(seed -> seedSpans.computeIfAbsent(seed, ignored -> new LinkedHashSet<>()).add(match.span()));
        }

        List<String> unresolved = new ArrayList<>();
        for (String seed : seeds) {
            Concept concept = index.concepts.get(seed);
            if (concept == null || concept.table() == null) {
                unresolved.add("概念 " + seed + " 没有映射物理表");
            }
        }
        List<JoinPath> paths = new ArrayList<>();
        List<String> seedList = new ArrayList<>(seeds);
        boolean disconnected = false;
        for (int i = 0; i < seedList.size(); i++) {
            for (int j = i + 1; j < seedList.size(); j++) {
                if (alternatives(seedSpans.get(seedList.get(i)), seedSpans.get(seedList.get(j)))) {
                    continue;
                }
                List<JoinPath> found = pathFinder.findPaths(index, seedList.get(i), seedList.get(j));
                if (found.isEmpty()) {
                    disconnected = true;
                    unresolved.add(seedList.get(i) + " 与 " + seedList.get(j) + " 之间没有关系路径");
                }
                paths.addAll(found);
            }
        }
        paths.forEach(path -> path.steps().forEach(step -> relations.add(step.relation())));

        String status;
        double confidence;
        if (seeds.isEmpty()) {
            status = OntologyTools.STATUS_NO_MATCH;
            confidence = 0;
        } else {
            confidence = matches.stream().mapToDouble(Match::quality).average().orElse(0);
            boolean ambiguous = warnings.stream().anyMatch(warning -> warning.startsWith("歧义"));
            status = ambiguous ? OntologyTools.STATUS_AMBIGUOUS : OntologyTools.STATUS_OK;
            if (ambiguous) {
                confidence *= 0.6;
            }
            if (disconnected) {
                confidence *= 0.8;
            }
        }
        List<DefinedView> definedViews = defined.stream()
                .map(name -> OntologyEntityViews.definedView(index, index.defined.get(name)))
                .sorted((a, b) -> Boolean.compare(b.certified(), a.certified()))
                .toList();
        for (DefinedView view : definedViews) {
            if (!view.certified()) {
                warnings.add("派生概念 " + view.name() + " 尚未审定（certified=false），口径请人工确认");
            }
        }
        List<ConceptView> conceptViews = seeds.stream()
                .filter(index.concepts::containsKey)
                .map(seed -> views.conceptView(index, seed))
                .sorted((a, b) -> Boolean.compare(b.certified(), a.certified()))
                .toList();
        long certifiedHits = definedViews.stream().filter(DefinedView::certified).count()
                + conceptViews.stream().filter(ConceptView::certified).count();
        if (certifiedHits > 0 && OntologyTools.STATUS_OK.equals(status)) {
            confidence = Math.min(1.0, confidence + 0.05 * Math.min(3, certifiedHits));
        }
        return new BusinessContext(
                status,
                round(confidence),
                matches.stream().map(m -> new EntityHit(m.entity(), m.kind(), index.labelOf(m.entity()), m.text(),
                        m.source(), round(m.quality()))).toList(),
                conceptViews,
                attributes.stream().map(name -> OntologyEntityViews.attributeView(index, index.attributes.get(name))).toList(),
                relations.stream().filter(index.relations::containsKey)
                        .map(name -> OntologyEntityViews.relationView(index, index.relations.get(name))).toList(),
                definedViews,
                codes.stream().map(index.codes::get)
                        .map(code -> OntologyEntityViews.codeView(index, code))
                        .toList(),
                paths,
                warnings,
                unresolved);
    }

    /**
     * 同一个词命中多个概念的码值时，若问题里另外提到了其中某个概念（或它的属性），只保留该概念的码值。
     */
    private static List<Match> lockCodeConcepts(OntologyIndex index, List<Match> matches, List<String> warnings) {
        Set<String> mentioned = new LinkedHashSet<>();
        for (Match match : matches) {
            switch (match.kind()) {
                case "concept" -> mentioned.add(match.entity());
                case "attribute" -> addSeed(mentioned, index.attributes.get(match.entity()).domain());
                case "defined" -> addSeed(mentioned, index.defined.get(match.entity()).base());
                default -> {
                }
            }
        }
        if (mentioned.isEmpty()) {
            return matches;
        }
        Map<String, List<Match>> bySpan = new LinkedHashMap<>();
        matches.stream().filter(match -> "code".equals(match.kind()))
                .forEach(match -> bySpan.computeIfAbsent(match.span(), ignored -> new ArrayList<>()).add(match));
        Set<Match> dropped = new HashSet<>();
        for (List<Match> group : bySpan.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<Match> kept = group.stream().filter(match -> {
                Attribute owner = index.codeOwner(index.codes.get(match.entity()).codeList());
                return owner != null && mentioned.contains(owner.domain());
            }).toList();
            if (kept.isEmpty() || kept.size() == group.size()) {
                continue;
            }
            group.stream().filter(match -> !kept.contains(match)).forEach(dropped::add);
            Set<String> removed = group.stream().filter(dropped::contains).map(Match::entity).collect(Collectors.toSet());
            warnings.removeIf(warning -> warning.startsWith("歧义") && removed.stream().anyMatch(warning::contains)
                    && kept.size() == 1);
            warnings.add("「" + group.get(0).text() + "」按问题中的概念锁定为码值 "
                    + kept.stream().map(Match::entity).toList());
        }
        if (dropped.isEmpty()) {
            return matches;
        }
        return new ArrayList<>(matches.stream().filter(match -> !dropped.contains(match)).toList());
    }

    private static boolean alternatives(Set<String> left, Set<String> right) {
        return left.size() == 1 && left.equals(right);
    }

    private static void addSeed(Set<String> seeds, String concept) {
        if (concept != null) {
            seeds.add(concept);
        }
    }

    public static List<Match> match(String question, List<Term> lexicon, List<String> warnings) {
        String text = question.toLowerCase(Locale.ROOT);
        List<Occurrence> occurrences = new ArrayList<>();
        for (Term term : lexicon) {
            int from = 0;
            while (true) {
                int at = text.indexOf(term.text(), from);
                if (at < 0) {
                    break;
                }
                occurrences.add(new Occurrence(term, at, at + term.text().length()));
                from = at + 1;
            }
        }
        occurrences.sort(Comparator.comparingInt((Occurrence o) -> o.end() - o.start()).reversed()
                .thenComparing(Comparator.comparingDouble((Occurrence o) -> o.term().quality()).reversed()));

        boolean[] covered = new boolean[text.length()];
        Map<String, List<Occurrence>> accepted = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            String span = occurrence.start() + ":" + occurrence.end();
            if (!accepted.containsKey(span) && anyCovered(covered, occurrence)) {
                continue;
            }
            accepted.computeIfAbsent(span, ignored -> new ArrayList<>()).add(occurrence);
            for (int i = occurrence.start(); i < occurrence.end(); i++) {
                covered[i] = true;
            }
        }

        Map<String, Match> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Occurrence>> entry : accepted.entrySet()) {
            List<Occurrence> group = entry.getValue();
            double top = group.stream().mapToDouble(o -> o.term().quality()).max().orElse(0);
            List<Occurrence> best = group.stream().filter(o -> o.term().quality() == top).toList();
            Set<String> entities = best.stream().map(o -> o.term().entity()).collect(Collectors.toCollection(LinkedHashSet::new));
            if (entities.size() > 1) {
                warnings.add("歧义：「" + best.get(0).term().original() + "」同时对应 " + entities);
            }
            for (Occurrence occurrence : best) {
                Term term = occurrence.term();
                result.merge(term.entity(),
                        new Match(term.entity(), term.kind(), term.original(), term.source(), term.quality(), entry.getKey()),
                        (a, b) -> a.quality() >= b.quality() ? a : b);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean anyCovered(boolean[] covered, Occurrence occurrence) {
        for (int i = occurrence.start(); i < occurrence.end(); i++) {
            if (covered[i]) {
                return true;
            }
        }
        return false;
    }

    public static List<Term> lexicon(Snapshot snapshot) {
        List<Term> terms = new ArrayList<>();
        snapshot.concepts().forEach(c -> addTerms(terms, c.name(), "concept", c.labelZh(), c.labelEn(), c.aliases()));
        snapshot.attributes().forEach(a -> addTerms(terms, a.name(), "attribute", a.labelZh(), a.labelEn(), a.aliases()));
        snapshot.relations().forEach(r -> addTerms(terms, r.name(), "relation", r.labelZh(), r.labelEn(), r.aliases()));
        snapshot.definedConcepts().forEach(d -> addTerms(terms, d.name(), "defined", d.labelZh(), d.labelEn(), d.aliases()));
        snapshot.codes().forEach(c -> addTerms(terms, c.name(), "code", c.labelZh(), c.labelEn(), c.aliases()));
        return terms;
    }

    private static void addTerms(List<Term> terms, String name, String kind, String labelZh, String labelEn, List<String> aliases) {
        String label = labelZh != null ? labelZh : name;
        addTerm(terms, name, kind, label, labelZh, "label", 1.0);
        addTerm(terms, name, kind, label, name, "name", 1.0);
        addTerm(terms, name, kind, label, labelEn, "labelEn", 0.9);
        if (aliases != null) {
            aliases.forEach(alias -> addTerm(terms, name, kind, label, alias, "alias", 0.85));
        }
    }

    private static void addTerm(List<Term> terms, String entity, String kind, String label, String text, String source, double quality) {
        if (text == null || text.isBlank() || text.trim().length() < 2) {
            return;
        }
        terms.add(new Term(entity, kind, label, text.trim(), text.trim().toLowerCase(Locale.ROOT), source, quality));
    }

    public static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    public record Term(String entity, String kind, String label, String original, String text, String source, double quality) {
    }

    public record Occurrence(Term term, int start, int end) {
    }

    /**
     * @param span 命中的问题片段位置；同一片段的多个候选互为歧义备选，不要求它们之间有关系路径
     */
    public record Match(String entity, String kind, String text, String source, double quality, String span) {
    }
}
