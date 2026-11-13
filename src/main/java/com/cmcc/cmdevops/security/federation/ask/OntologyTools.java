package com.cmcc.cmdevops.security.federation.ask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HttpTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.NgramTermRetriever;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.TermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyEntityViews;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyPathFinder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ontology 智能体的只读工具。只回答业务语义：概念、属性、码值、关系路径、派生定义；
 * 物理表是否真实存在由 {@link MetadataVerifier} 负责。
 */
@Component
public class OntologyTools {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_AMBIGUOUS = "ambiguous";
    public static final String STATUS_NO_MATCH = "no_match";

    private final OntologyRepository repository;
    private final OntologyPathFinder pathFinder;
    private final OntologyEntityViews views;
    private final BusinessContextBuilder contextBuilder;

    @Autowired
    public OntologyTools(OntologyRepository repository, OntologyQueryService queryService, FederationProperties properties) {
        this(
                repository,
                queryService,
                properties.getAsk().getMaxPathDepth(),
                termEmbedderOf(properties),
                embedCacheDirOf(properties));
    }

    OntologyTools(OntologyRepository repository, OntologyQueryService queryService, int maxDepth) {
        this(repository, queryService, maxDepth, new HashingTermEmbedder(), null);
    }

    OntologyTools(OntologyRepository repository, OntologyQueryService queryService, int maxDepth, TermEmbedder embedder) {
        this(repository, queryService, maxDepth, embedder, null);
    }

    OntologyTools(
            OntologyRepository repository,
            OntologyQueryService queryService,
            int maxDepth,
            TermEmbedder embedder,
            Path embeddingCacheDir) {
        this.repository = repository;
        this.pathFinder = new OntologyPathFinder(maxDepth);
        this.views = new OntologyEntityViews(queryService);
        this.contextBuilder = new BusinessContextBuilder(repository, pathFinder, views, embedder, embeddingCacheDir);
    }

    static TermEmbedder termEmbedderOf(FederationProperties properties) {
        String endpoint = properties.getAsk().getEmbedEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return new HashingTermEmbedder();
        }
        return new HttpTermEmbedder(endpoint, properties.getAsk().getEmbedApiKey(), properties.getAsk().getEmbedModel());
    }

    static Path embedCacheDirOf(FederationProperties properties) {
        String configured = properties.getAsk().getEmbedCacheDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        Path store = Path.of(properties.getOntologyStore() == null ? "data/tdb2" : properties.getOntologyStore());
        Path parent = store.getParent();
        return parent == null ? Path.of("data/ontology-embeddings") : parent.resolve("ontology-embeddings");
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
        for (Term term : BusinessContextBuilder.lexicon(repository.snapshot())) {
            double score;
            if (term.text().equals(needle)) {
                score = term.quality();
            } else if (term.text().contains(needle) || needle.contains(term.text())) {
                score = term.quality() * 0.6;
            } else if (needle.length() >= 2 && term.text().length() >= 2
                    && levenshtein(term.text(), needle) <= 1) {
                // 轻量模糊召回（编辑距离 ≤1），弥补无向量检索时的近邻词
                score = term.quality() * 0.35;
            } else {
                TermEmbedder embedder = contextBuilder.termEmbedder();
                double embedSim = HashingTermEmbedder.cosine(embedder.embed(needle), embedder.embed(term.text()));
                if (embedSim >= 0.45) {
                    score = term.quality() * (0.35 + 0.45 * embedSim);
                } else {
                    double sim = NgramTermRetriever.cosine(
                            NgramTermRetriever.grams(needle), NgramTermRetriever.grams(term.text()));
                    if (sim < 0.55) {
                        continue;
                    }
                    score = term.quality() * (0.3 + 0.4 * sim);
                }
            }
            hits.add(new EntityHit(term.entity(), term.kind(), term.label(), term.original(), term.source(),
                    BusinessContextBuilder.round(score)));
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
        OntologyIndex index = new OntologyIndex(snapshot);
        if (index.concepts.containsKey(name)) {
            return views.conceptView(index, name);
        }
        if (index.attributes.containsKey(name)) {
            return OntologyEntityViews.attributeView(index, index.attributes.get(name));
        }
        if (index.relations.containsKey(name)) {
            return OntologyEntityViews.relationView(index, index.relations.get(name));
        }
        if (index.defined.containsKey(name)) {
            return OntologyEntityViews.definedView(index, index.defined.get(name));
        }
        Code code = index.codes.get(name);
        if (code != null) {
            return OntologyEntityViews.codeView(index, code);
        }
        return Map.of("error", "本体中没有实体 " + name + "，请先用 searchEntities 检索");
    }

    @Tool("查找两个业务概念之间的最短关系路径（可多跳），返回每一步的关系和 Join 条件")
    public List<JoinPath> findPaths(@P("起点概念名称") String from, @P("终点概念名称") String to) {
        return pathFinder.findPaths(new OntologyIndex(repository.snapshot()), from, to);
    }

    @Tool("列出本体中全部派生业务概念（如高危告警、失陷主机）的正式定义")
    public List<DefinedView> listDefinedConcepts() {
        OntologyIndex index = new OntologyIndex(repository.snapshot());
        return index.defined.values().stream()
                .map(defined -> OntologyEntityViews.definedView(index, defined))
                .toList();
    }

    @Tool("对一个业务问题做确定性的本体检索：识别问题中的概念、属性、码值、派生概念，并给出它们之间的关系路径和置信度")
    public BusinessContext getBusinessContext(@P("用户的原始问题") String question) {
        return contextBuilder.businessContext(question, Set.of());
    }

    @Tool("廉价可回答性预检：不写 SQL。返回 answerable、coverage、已匹配术语、缺口与建议；覆盖不足时应先向用户澄清")
    public AnswerabilityChecker.Answerability checkAnswerable(@P("用户的原始问题") String question) {
        BusinessContext context = contextBuilder.businessContext(question, Set.of());
        AnswerabilityChecker.Answerability result = AnswerabilityChecker.check(context, listConcepts(), 0.5);
        List<String> uncovered = AnswerabilityChecker.uncoveredPhrases(question, context);
        if (!uncovered.isEmpty() && !result.answerable()) {
            List<String> gaps = new ArrayList<>(result.gaps());
            gaps.add("可能未覆盖：" + String.join("、", uncovered));
            return new AnswerabilityChecker.Answerability(
                    result.answerable(), result.coverage(), result.status(),
                    result.matchedTerms(), gaps, result.suggestion());
        }
        return result;
    }

    /**
     * @param extraEntities 恢复检索时补充的实体名，与问题中的词面命中合并
     */
    public BusinessContext businessContext(String question, Set<String> extraEntities) {
        return contextBuilder.businessContext(question, extraEntities);
    }

    /**
     * 只用多轮工具 Agent 选定的实体构造上下文，不再做词面匹配（歧义已由 Agent 消解）。
     */
    public BusinessContext contextOf(Set<String> entities) {
        return contextBuilder.contextOf(entities);
    }

    public record ConceptSummary(String name, String labelZh, List<String> aliases, String table) {
    }

    public record EntityHit(String name, String kind, String labelZh, String matchedText, String matchedBy, double score) {
    }

    /** concept/attribute/column 是这个码值唯一所属的概念及其码值列，生成 SQL 时只能用在这一列上。 */
    public record CodeView(
            String name,
            String codeList,
            String value,
            String labelZh,
            List<String> aliases,
            String concept,
            String attribute,
            String column) {
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
            boolean certified,
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
            String definitionText,
            String sqlExpression,
            boolean certified) {
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

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
