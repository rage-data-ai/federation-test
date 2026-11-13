package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ontology 智能体的只读工具。只回答业务语义：概念、属性、码值、关系路径、派生定义；
 * 物理表是否真实存在由 {@link MetadataVerifier} 负责。
 */
@Component
public class OntologyTools {

    static final String STATUS_OK = "ok";
    static final String STATUS_AMBIGUOUS = "ambiguous";
    static final String STATUS_NO_MATCH = "no_match";

    private static final Set<String> TEXT_TYPES = Set.of("string", "date", "dateTime");
    private static final int MAX_PATHS = 5;

    private final OntologyRepository repository;
    private final OntologyQueryService queryService;
    private final int maxDepth;

    @Autowired
    public OntologyTools(OntologyRepository repository, OntologyQueryService queryService, FederationProperties properties) {
        this(repository, queryService, properties.getAsk().getMaxPathDepth());
    }

    OntologyTools(OntologyRepository repository, OntologyQueryService queryService, int maxDepth) {
        this.repository = repository;
        this.queryService = queryService;
        this.maxDepth = maxDepth;
    }

    @Tool("列出本体中全部业务概念（类）的名称、中文名、别名和映射的表，用于了解有哪些对象可以分析")
    public List<ConceptSummary> listConcepts() {
        return repository.snapshot().concepts().stream()
                .map(c -> new ConceptSummary(c.name(), c.labelZh(), c.aliases(), c.table()))
                .toList();
    }

    @Tool("按关键词（中文名、别名、英文名或名称）检索本体实体，返回候选及匹配分数")
    public List<EntityHit> searchEntities(@P("业务关键词，例如：告警、高危、资产名称") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        List<EntityHit> hits = new ArrayList<>();
        for (Term term : lexicon(repository.snapshot())) {
            double score;
            if (term.text().equals(needle)) {
                score = term.quality();
            } else if (term.text().contains(needle) || needle.contains(term.text())) {
                score = term.quality() * 0.6;
            } else {
                continue;
            }
            hits.add(new EntityHit(term.entity(), term.kind(), term.label(), term.original(), term.source(), round(score)));
        }
        Map<String, EntityHit> best = new LinkedHashMap<>();
        hits.stream()
                .sorted(Comparator.comparingDouble(EntityHit::score).reversed())
                .forEach(hit -> best.putIfAbsent(hit.name(), hit));
        return best.values().stream().limit(10).toList();
    }

    @Tool("查看一个本体实体的完整描述：类返回映射表、时间列、属性及码值、子类和相关关系；属性返回码值；派生概念返回定义和可用的 SQL 条件")
    public Object describeEntity(@P("实体名称，例如 Alert、alertLevel、HighRiskAlert") String name) {
        Snapshot snapshot = repository.snapshot();
        Index index = new Index(snapshot);
        if (index.concepts.containsKey(name)) {
            return conceptView(index, name);
        }
        if (index.attributes.containsKey(name)) {
            return attributeView(index, index.attributes.get(name));
        }
        if (index.relations.containsKey(name)) {
            return relationView(index, index.relations.get(name));
        }
        if (index.defined.containsKey(name)) {
            return definedView(index, index.defined.get(name));
        }
        Code code = index.codes.get(name);
        if (code != null) {
            return new CodeView(code.name(), code.codeList(), code.value(), code.labelZh(), code.aliases());
        }
        return Map.of("error", "本体中没有实体 " + name + "，请先用 searchEntities 检索");
    }

    @Tool("查找两个业务概念之间的最短关系路径（可多跳），返回每一步的关系和 Join 条件")
    public List<JoinPath> findPaths(@P("起点概念名称") String from, @P("终点概念名称") String to) {
        return paths(new Index(repository.snapshot()), from, to);
    }

    @Tool("列出本体中全部派生业务概念（如高危告警、失陷主机）的正式定义")
    public List<DefinedView> listDefinedConcepts() {
        Index index = new Index(repository.snapshot());
        return index.defined.values().stream().map(defined -> definedView(index, defined)).toList();
    }

    @Tool("对一个业务问题做确定性的本体检索：识别问题中的概念、属性、码值、派生概念，并给出它们之间的关系路径和置信度")
    public BusinessContext getBusinessContext(@P("用户的原始问题") String question) {
        return businessContext(question, Set.of());
    }

    /**
     * @param extraEntities 恢复检索时补充的实体名，与问题中的词面命中合并
     */
    public BusinessContext businessContext(String question, Set<String> extraEntities) {
        return build(question, extraEntities, false);
    }

    /**
     * 只用多轮工具 Agent 选定的实体构造上下文，不再做词面匹配（歧义已由 Agent 消解）。
     */
    public BusinessContext contextOf(Set<String> entities) {
        return build(null, entities, true);
    }

    private BusinessContext build(String question, Set<String> extraEntities, boolean exclusive) {
        Snapshot snapshot = repository.snapshot();
        Index index = new Index(snapshot);
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
                    index.attributes.values().stream()
                            .filter(attribute -> Objects.equals(attribute.codeList(), code.codeList()))
                            .forEach(attribute -> {
                                attributes.add(attribute.name());
                                addSeed(matchSeeds, attribute.domain());
                            });
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
                List<JoinPath> found = paths(index, seedList.get(i), seedList.get(j));
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
            status = STATUS_NO_MATCH;
            confidence = 0;
        } else {
            confidence = matches.stream().mapToDouble(Match::quality).average().orElse(0);
            boolean ambiguous = warnings.stream().anyMatch(warning -> warning.startsWith("歧义"));
            status = ambiguous ? STATUS_AMBIGUOUS : STATUS_OK;
            if (ambiguous) {
                confidence *= 0.6;
            }
            if (disconnected) {
                confidence *= 0.8;
            }
        }
        return new BusinessContext(
                status,
                round(confidence),
                matches.stream().map(m -> new EntityHit(m.entity(), m.kind(), index.labelOf(m.entity()), m.text(),
                        m.source(), round(m.quality()))).toList(),
                seeds.stream().filter(index.concepts::containsKey).map(seed -> conceptView(index, seed)).toList(),
                attributes.stream().map(name -> attributeView(index, index.attributes.get(name))).toList(),
                relations.stream().filter(index.relations::containsKey)
                        .map(name -> relationView(index, index.relations.get(name))).toList(),
                defined.stream().map(name -> definedView(index, index.defined.get(name))).toList(),
                codes.stream().map(index.codes::get)
                        .map(code -> new CodeView(code.name(), code.codeList(), code.value(), code.labelZh(), code.aliases()))
                        .toList(),
                paths,
                warnings,
                unresolved);
    }

    private static boolean alternatives(Set<String> left, Set<String> right) {
        return left.size() == 1 && left.equals(right);
    }

    private static void addSeed(Set<String> seeds, String concept) {
        if (concept != null) {
            seeds.add(concept);
        }
    }

    static List<Match> match(String question, List<Term> lexicon, List<String> warnings) {
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

    static List<Term> lexicon(Snapshot snapshot) {
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

    private List<JoinPath> paths(Index index, String from, String to) {
        String start = index.conceptFor(from);
        String goal = index.conceptFor(to);
        if (start == null || goal == null) {
            return List.of();
        }
        if (start.equals(goal)) {
            return List.of(new JoinPath(start, goal, List.of()));
        }
        Map<String, List<PathStep>> edges = new HashMap<>();
        for (Relation relation : index.relations.values()) {
            if (relation.domain() == null || relation.range() == null) {
                continue;
            }
            String join = joinText(index, relation);
            edges.computeIfAbsent(relation.domain(), ignored -> new ArrayList<>())
                    .add(new PathStep(relation.name(), relation.labelZh(), relation.domain(), relation.range(), join, false));
            edges.computeIfAbsent(relation.range(), ignored -> new ArrayList<>())
                    .add(new PathStep(relation.name(), relation.labelZh(), relation.range(), relation.domain(), join, true));
        }
        List<JoinPath> found = new ArrayList<>();
        Deque<List<PathStep>> queue = new ArrayDeque<>();
        queue.add(List.of());
        int shortest = Integer.MAX_VALUE;
        while (!queue.isEmpty() && found.size() < MAX_PATHS) {
            List<PathStep> path = queue.poll();
            if (path.size() >= maxDepth || path.size() >= shortest) {
                continue;
            }
            String node = path.isEmpty() ? start : path.get(path.size() - 1).to();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(start);
            path.forEach(step -> visited.add(step.to()));
            for (PathStep step : edges.getOrDefault(node, List.of())) {
                if (visited.contains(step.to())) {
                    continue;
                }
                List<PathStep> next = new ArrayList<>(path);
                next.add(step);
                if (step.to().equals(goal)) {
                    shortest = next.size();
                    found.add(new JoinPath(start, goal, next));
                } else {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    private static String joinText(Index index, Relation relation) {
        if (relation.joinFrom() == null || relation.joinTo() == null) {
            return null;
        }
        return tableOf(index, relation.domain()) + "." + relation.joinFrom()
                + " = " + tableOf(index, relation.range()) + "." + relation.joinTo();
    }

    private static String tableOf(Index index, String concept) {
        Concept c = index.concepts.get(concept);
        return c == null || c.table() == null ? concept : c.table();
    }

    private ConceptView conceptView(Index index, String name) {
        Concept concept = index.concepts.get(name);
        List<AttributeView> attributes = index.attributes.values().stream()
                .filter(attribute -> name.equals(attribute.domain()))
                .map(attribute -> attributeView(index, attribute))
                .toList();
        List<String> related = index.relations.values().stream()
                .filter(relation -> name.equals(relation.domain()) || name.equals(relation.range()))
                .map(Relation::name)
                .toList();
        List<String> derived = index.defined.values().stream()
                .filter(defined -> name.equals(defined.base()))
                .map(DefinedConcept::name)
                .toList();
        return new ConceptView(concept.name(), concept.labelZh(), concept.aliases(), concept.comment(), concept.parent(),
                subConcepts(name), concept.table(), concept.timeColumn(), concept.largeTable(), attributes, related, derived);
    }

    private List<String> subConcepts(String name) {
        String sparql = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "SELECT DISTINCT ?c WHERE { ?c rdfs:subClassOf <" + OntologyVocabulary.iri(name) + "> "
                + "FILTER(isIRI(?c) && ?c != <" + OntologyVocabulary.iri(name) + "> "
                + "&& STRSTARTS(STR(?c), \"" + OntologyVocabulary.SOC + "\")) } ORDER BY ?c";
        return queryService.select(sparql, true).rows().stream()
                .map(row -> OntologyVocabulary.localName(row.get(0)))
                .toList();
    }

    private static AttributeView attributeView(Index index, Attribute attribute) {
        List<CodeView> codes = attribute.codeList() == null ? List.of() : index.codes.values().stream()
                .filter(code -> attribute.codeList().equals(code.codeList()))
                .map(code -> new CodeView(code.name(), code.codeList(), code.value(), code.labelZh(), code.aliases()))
                .toList();
        return new AttributeView(attribute.name(), attribute.labelZh(), attribute.aliases(), attribute.comment(),
                attribute.domain(), attribute.column(), attribute.datatype(), attribute.unit(), attribute.identifier(),
                attribute.codeList(), codes);
    }

    private static RelationView relationView(Index index, Relation relation) {
        return new RelationView(relation.name(), relation.labelZh(), relation.aliases(), relation.comment(),
                relation.domain(), relation.range(), joinText(index, relation), relation.inverse(),
                relation.transitive(), relation.functional());
    }

    static DefinedView definedView(Index index, DefinedConcept defined) {
        List<String> readable = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        for (Condition condition : defined.conditions()) {
            Attribute attribute = index.attributes.get(condition.attribute());
            String label = attribute == null || attribute.labelZh() == null ? condition.attribute() : attribute.labelZh();
            readable.add(label + " " + condition.operator() + " " + condition.value());
            if (attribute != null && attribute.column() != null) {
                boolean text = TEXT_TYPES.contains(Objects.toString(attribute.datatype(), "string"));
                String value = text ? "'" + condition.value().replace("'", "''") + "'" : condition.value();
                sql.add(attribute.column() + " " + condition.operator() + " " + value);
            }
        }
        Concept base = index.concepts.get(defined.base());
        return new DefinedView(defined.name(), defined.labelZh(), defined.aliases(), defined.comment(), defined.base(),
                base == null ? null : base.table(), readable,
                sql.isEmpty() ? null : String.join(" AND ", sql), defined.definitionText());
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static final class Index {
        final Map<String, Concept> concepts;
        final Map<String, Attribute> attributes;
        final Map<String, Relation> relations;
        final Map<String, DefinedConcept> defined;
        final Map<String, Code> codes;

        Index(Snapshot snapshot) {
            concepts = byName(snapshot.concepts(), Concept::name);
            attributes = byName(snapshot.attributes(), Attribute::name);
            relations = byName(snapshot.relations(), Relation::name);
            defined = byName(snapshot.definedConcepts(), DefinedConcept::name);
            codes = byName(snapshot.codes(), Code::name);
        }

        String kindOf(String name) {
            if (concepts.containsKey(name)) {
                return "concept";
            }
            if (attributes.containsKey(name)) {
                return "attribute";
            }
            if (relations.containsKey(name)) {
                return "relation";
            }
            if (defined.containsKey(name)) {
                return "defined";
            }
            return codes.containsKey(name) ? "code" : null;
        }

        String conceptFor(String name) {
            if (concepts.containsKey(name)) {
                return name;
            }
            DefinedConcept d = defined.get(name);
            return d == null ? null : d.base();
        }

        String labelOf(String name) {
            if (concepts.containsKey(name)) {
                return concepts.get(name).labelZh();
            }
            if (attributes.containsKey(name)) {
                return attributes.get(name).labelZh();
            }
            if (relations.containsKey(name)) {
                return relations.get(name).labelZh();
            }
            if (defined.containsKey(name)) {
                return defined.get(name).labelZh();
            }
            return codes.containsKey(name) ? codes.get(name).labelZh() : null;
        }

        private static <T> Map<String, T> byName(List<T> items, Function<T, String> name) {
            Map<String, T> map = new LinkedHashMap<>();
            items.forEach(item -> map.put(name.apply(item), item));
            return map;
        }
    }

    record Term(String entity, String kind, String label, String original, String text, String source, double quality) {
    }

    record Occurrence(Term term, int start, int end) {
    }

    /**
     * @param span 命中的问题片段位置；同一片段的多个候选互为歧义备选，不要求它们之间有关系路径
     */
    record Match(String entity, String kind, String text, String source, double quality, String span) {
    }

    public record ConceptSummary(String name, String labelZh, List<String> aliases, String table) {
    }

    public record EntityHit(String name, String kind, String labelZh, String matchedText, String matchedBy, double score) {
    }

    public record CodeView(String name, String codeList, String value, String labelZh, List<String> aliases) {
    }

    public record AttributeView(
            String name,
            String labelZh,
            List<String> aliases,
            String comment,
            String domain,
            String column,
            String datatype,
            String unit,
            boolean identifier,
            String codeList,
            List<CodeView> codes) {
    }

    public record ConceptView(
            String name,
            String labelZh,
            List<String> aliases,
            String comment,
            String parent,
            List<String> subConcepts,
            String table,
            String timeColumn,
            boolean largeTable,
            List<AttributeView> attributes,
            List<String> relations,
            List<String> definedConcepts) {
    }

    public record RelationView(
            String name,
            String labelZh,
            List<String> aliases,
            String comment,
            String domain,
            String range,
            String join,
            String inverse,
            boolean transitive,
            boolean functional) {
    }

    public record DefinedView(
            String name,
            String labelZh,
            List<String> aliases,
            String comment,
            String base,
            String baseTable,
            List<String> conditions,
            String sqlCondition,
            String definitionText) {
    }

    public record PathStep(String relation, String labelZh, String from, String to, String join, boolean reversed) {
    }

    public record JoinPath(String from, String to, List<PathStep> steps) {
    }

    public record BusinessContext(
            String status,
            double confidence,
            List<EntityHit> matches,
            List<ConceptView> concepts,
            List<AttributeView> attributes,
            List<RelationView> relations,
            List<DefinedView> definedConcepts,
            List<CodeView> codes,
            List<JoinPath> paths,
            List<String> warnings,
            List<String> unresolved) {
    }
}
