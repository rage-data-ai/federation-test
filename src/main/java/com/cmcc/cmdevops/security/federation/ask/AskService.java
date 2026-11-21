package com.cmcc.cmdevops.security.federation.ask;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.ask.MetadataVerifier.MetadataReport;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptSummary;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.feedback.MemoryAskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService.Match;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService;
import com.cmcc.cmdevops.security.federation.ask.prompt.PreferenceTools;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.llm.JsonReply;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.service.QueryService;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * 智能问数编排：知识库（可选短路）→ Ontology → Metadata → DataInsight。
 * <p>
 * 事件：run、stage、knowledge、ontology、metadata、sql、result、clarify、answer、error，最后由调用方发 done。
 * 每次问数结束后落一条 {@link com.cmcc.cmdevops.security.federation.ask.feedback.AskRun}，供评价和建议引用。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    static final String EXPLORER_PROMPT = """
            你是安全运营本体的检索助手，只负责把用户问题对应到本体实体，不写 SQL。
            用工具 listConcepts、searchEntities、describeEntity、findPaths、listDefinedConcepts、checkAnswerable 探索本体，
            找出回答问题需要的概念、属性、码值、关系和派生概念（用实体 name，区分大小写）。
            可先 checkAnswerable 看覆盖率；有歧义时优先结合问题上下文消解；
            确实无法判断，或本体里没有问题需要的业务对象时，向用户澄清（说明能答什么、不能答什么）。
            最后只输出一个 JSON 对象，不要其它文字：
            {"entities": ["Alert", "alertLevel"], "clarify": null}
            或
            {"entities": [], "clarify": {"question": "要问用户的问题", "options": ["选项1", "选项2"]}}
            """;

    static final String INSIGHT_PROMPT = """
            你是安全运营数据分析师，基于"业务上下文"（来自本体）和"元数据"（来自真实采集）为用户问题规划并执行 Trino SQL。

            规划步骤：
            1. 明确问题要的指标、维度、过滤条件和时间范围。
            2. 从业务上下文中找到对应的概念、属性、码值、派生概念和关系路径。
            3. 只用元数据 tables 中列出的表和列；表名必须写 catalog.schema.table；
               列名含大写字母时用双引号，如 "assetId"。
            4. 码值过滤必须用码值的 value，禁止把中文名写进 WHERE；派生概念有 sqlCondition 时必须直接复用该条件，
               不要自行改写阈值。派生概念有 sqlExpression 时必须作为 SELECT 度量表达式复用（SQL attributes），
               不要另写不等价的聚合。只有 definitionText 时才按其文字定义实现。
               多个口径候选时优先使用 certified=true 的派生概念/概念。
            5. 跨概念用关系路径给出的 join 条件，不要自己猜 join 列。
            6. 元数据里 timeColumn 不为空的表是大表，WHERE 中必须对该列做范围过滤。
            7. 写入等值过滤前，对有码表的列调用 validateFilterValues；结果为空时调用 scanFilterLiterals 检查字面量。

            口径规则：
            - 问题缺少必要口径（例如大表查询没有给时间范围、"高危"等短语在本体中没有定义、有多种合理理解）时，
              不要自己编门槛，直接用 clarify 询问用户，并给出 2~4 个选项。
            - 使用了本体定义的口径记 source=ontology；你自己推断的（如排序方式、取前几名）记 source=inferred；
              用户在对话中明确说的记 source=user。

            执行规则：
            - 用 runSql 执行，被拒绝或失败时根据原因修正后重试。
            - Trino 报表或列不存在时可调用一次 recoverMetadata；业务上下文缺概念时可调用一次 recoverOntology。
            - 结果为空、全 0 等退化形态时，最多追加 1 次 diagnostic 查询排查原因，然后作答。
            - 若本体覆盖明显不足（缺少关键业务对象），用 clarify 说明能答什么、不能答什么，而不是编造 SQL。

            最后只输出一个 JSON 对象，不要其它文字：
            {"status": "answered", "answer": "结论（中文，引用关键数字）", "sql": "最终回答所依据的 SQL",
             "caliber": [{"item": "高危告警", "source": "ontology", "detail": "level >= 3"}]}
            或
            {"status": "clarify", "question": "要问用户的问题", "options": ["选项1", "选项2"]}
            """;

    private final OntologyTools ontologyTools;
    private final MetadataVerifier verifier;
    private final Function<String, QueryResponse> runner;
    private final Consumer<String> validator;
    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final FederationProperties.Ask config;
    private final AskFeedbackStore feedbackStore;
    private final AskKnowledgeService knowledge;
    private final AskPromptService prompts;
    private final PreferenceTools preferenceTools;

    @Autowired
    public AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            SqlGuard guard,
            QueryService queryService,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties properties,
            AskFeedbackStore feedbackStore,
            AskKnowledgeService knowledge,
            AskPromptService prompts,
            PreferenceTools preferenceTools) {
        this(ontologyTools, verifier, sql -> execute(queryService, sql), guard::validate, llm, objectMapper,
                properties.getAsk(), feedbackStore, knowledge, prompts, preferenceTools);
    }

    AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties.Ask config) {
        this(ontologyTools, verifier, runner, validator, llm, objectMapper, config, new MemoryAskFeedbackStore(), null,
                null, null);
    }

    AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties.Ask config,
            AskFeedbackStore feedbackStore) {
        this(ontologyTools, verifier, runner, validator, llm, objectMapper, config, feedbackStore, null, null, null);
    }

    AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties.Ask config,
            AskFeedbackStore feedbackStore,
            AskKnowledgeService knowledge) {
        this(ontologyTools, verifier, runner, validator, llm, objectMapper, config, feedbackStore, knowledge, null, null);
    }

    AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties.Ask config,
            AskFeedbackStore feedbackStore,
            AskKnowledgeService knowledge,
            AskPromptService prompts,
            PreferenceTools preferenceTools) {
        this.ontologyTools = ontologyTools;
        this.verifier = verifier;
        this.runner = runner;
        this.validator = validator;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.config = config;
        this.feedbackStore = feedbackStore;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.preferenceTools = preferenceTools;
    }

    private String systemPrompt(String base) {
        return prompts == null ? base : prompts.compose(base);
    }

    interface Explorer {
        String explore(String prompt);
    }

    interface Analyst {
        String analyze(String prompt);
    }

    public record Turn(String role, String content) {
    }

    /**
     * @param conversationId 同一段对话的多次问数传同一个 id；不传时每次问数自成一段
     * @param appId          已发布应用 id；不传时走内置「智能问数」
     */
    public record AskRequest(String question, List<Turn> history, String conversationId, String appId) {

        public AskRequest(String question, List<Turn> history) {
            this(question, history, null, null);
        }

        public AskRequest(String question, List<Turn> history, String conversationId) {
            this(question, history, conversationId, null);
        }
    }

    public record Clarify(String question, List<String> options) {
    }

    public record CaliberItem(String item, String source, String detail) {
    }

    record ExplorerReply(List<String> entities, Clarify clarify) {
    }

    record AnalystReply(String status, String answer, String sql, List<CaliberItem> caliber, String question,
                        List<String> options) {
    }

    /** 公开的廉价可回答性预检，供 API / 控制台在发问前调用。 */
    public AnswerabilityChecker.Answerability answerability(String question) {
        BusinessContext context = ontologyTools.businessContext(question, Set.of());
        AnswerabilityChecker.Answerability result =
                AnswerabilityChecker.check(context, ontologyTools.listConcepts(), config.getMinConfidence());
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

    /** ChatFlow 白盒：打开一次 run 信封（只发一次 run 事件）。 */
    public AskRunRecorder beginRun(AskRequest request, AskListener listener) {
        return beginRun(request, listener, false);
    }

    /** @param preview 为 true 时 run 事件带 preview，且应配合 {@link #discardRun} 不落库。 */
    public AskRunRecorder beginRun(AskRequest request, AskListener listener, boolean preview) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        llm.model();
        AskRunRecorder recorder = new AskRunRecorder(
                request, GatewayUser.current().name(), listener, System.currentTimeMillis());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", recorder.id());
        run.put("conversationId", recorder.conversationId());
        if (request.appId() != null && !request.appId().isBlank()) {
            run.put("appId", request.appId());
        }
        if (preview) {
            run.put("preview", true);
        }
        listener.emit("run", run);
        return recorder;
    }

    public void finishRun(AskRunRecorder recorder) {
        saveRun(recorder);
    }

    /** 预览结束：不写正式问数历史。 */
    public void discardRun(AskRunRecorder recorder) {
        // no-op：预览只走 SSE，不落 fed_ask_run
    }

    public void failAndFinish(AskRunRecorder recorder, RuntimeException error) {
        recorder.failed(error);
        saveRun(recorder);
    }

    public void failAndDiscard(AskRunRecorder recorder, RuntimeException error) {
        recorder.failed(error);
    }

    /** 记录失败不影响已经给出的回答。 */
    private void saveRun(AskRunRecorder recorder) {
        try {
            feedbackStore.saveRun(recorder.toRun(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("问数记录保存失败 runId={}: {}", recorder.id(), e.toString());
        }
    }

    /**
     * 知识库阶段：只检索，不调大模型。outcome 固定 {@code default}，由下游 if_else / llm 判定分流。
     */
    public AskStageResult runKnowledge(AskRequest request, AskListener listener, AskFlowState state, int topK) {
        Map<String, Object> outputs = retrieveKnowledge(request, listener, Math.max(1, topK));
        return AskStageResult.of("default", outputs);
    }

    /**
     * 本体阶段。outcome：{@code continue} / {@code clarify}。
     * {@code skills} 含 {@link AskSkillIds#ONTOLOGY} 时才启用多轮工具探索。
     */
    public AskStageResult runOntology(
            AskRequest request, AskListener listener, AskFlowState state, Iterable<String> skills) {
        Set<String> skillSet = AskSkillIds.normalize(skills, AskSkillIds.DEFAULT_ONTOLOGY);
        listener.emit("stage", stage("ontology", "Ontology 智能体：检索业务语义"));
        BusinessContext context = ontologyTools.businessContext(userText(request), Set.of());
        AnswerabilityChecker.Answerability answerability =
                AnswerabilityChecker.check(context, ontologyTools.listConcepts(), config.getMinConfidence());
        listener.emit("answerability", answerability);

        boolean escalated = false;
        boolean allowExplore = skillSet.contains(AskSkillIds.ONTOLOGY);
        if (allowExplore
                && (!OntologyTools.STATUS_OK.equals(context.status())
                        || context.confidence() < config.getMinConfidence())) {
            listener.emit("ontology", Map.of("mode", "deterministic", "context", context));
            listener.emit("stage", stage("ontology", "置信度 " + context.confidence() + "，转入多轮工具探索"));
            ExplorerReply reply = explore(dialogue(request), context);
            if (reply.clarify() != null) {
                listener.emit("clarify", reply.clarify());
                state.setContext(context);
                return AskStageResult.of(
                        "clarify",
                        ontologyClarifyOutputs(context, answerability, reply.clarify()));
            }
            context = ontologyTools.contextOf(new LinkedHashSet<>(reply.entities() == null ? List.of() : reply.entities()));
            escalated = true;
            answerability = AnswerabilityChecker.check(context, ontologyTools.listConcepts(), config.getMinConfidence());
            listener.emit("answerability", answerability);
        }
        Map<String, Object> ontologyPayload = new LinkedHashMap<>();
        ontologyPayload.put("mode", escalated ? "explored" : "deterministic");
        ontologyPayload.put("context", context);
        ontologyPayload.put("skills", List.copyOf(skillSet));
        listener.emit("ontology", ontologyPayload);
        state.setContext(context);
        if (context.concepts().isEmpty()) {
            String clarifyQuestion = answerability.suggestion() == null || answerability.suggestion().isBlank()
                    ? "没有在本体中找到问题涉及的业务对象，你想分析的是哪一类？"
                    : "本体覆盖不足：" + answerability.suggestion();
            Clarify clarify = new Clarify(
                    clarifyQuestion,
                    ontologyTools.listConcepts().stream().map(ConceptSummary::labelZh).limit(6).toList());
            listener.emit("clarify", clarify);
            return AskStageResult.of("clarify", ontologyClarifyOutputs(context, answerability, clarify));
        }
        return AskStageResult.of(
                "continue", Map.of("context", contextSummary(context), "answerability", answerability));
    }

    /** 澄清时把 question/options 并入 context，下游用 {{#本体.context#}} 即可看到。 */
    private static Map<String, Object> ontologyClarifyOutputs(
            BusinessContext context, AnswerabilityChecker.Answerability answerability, Clarify clarify) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("context", contextSummary(context, clarify));
        outputs.put("answerability", answerability);
        outputs.put("clarify", clarifyPayload(clarify));
        return outputs;
    }

    static Map<String, Object> clarifyPayload(Clarify clarify) {
        Map<String, Object> pending = new LinkedHashMap<>();
        if (clarify == null) {
            return pending;
        }
        pending.put("question", clarify.question() == null ? "" : clarify.question());
        pending.put("options", clarify.options() == null ? List.of() : clarify.options());
        return pending;
    }

    static Map<String, Object> mergeClarify(Map<String, Object> target, Clarify clarify) {
        if (target != null && clarify != null) {
            target.put("clarify", clarifyPayload(clarify));
        }
        return target;
    }

    /**
     * 澄清并入主出参：Map 写入 clarify 键；字符串等则包成 {{text, clarify}}，供下游引用。
     */
    static Object mergeClarifyIntoPrimary(Object primary, Clarify clarify) {
        if (clarify == null) {
            return primary;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (primary instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    body.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else if (primary instanceof String text && !text.isBlank()) {
            body.put("text", text);
        } else if (primary != null && !(primary instanceof String)) {
            body.put("text", String.valueOf(primary));
        } else {
            body.put("text", "");
        }
        return mergeClarify(body, clarify);
    }

    @SuppressWarnings("unchecked")
    static Clarify clarifyFrom(Object raw) {
        if (raw instanceof Clarify clarify) {
            return clarify;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object question = map.get("question");
        Object optionsRaw = map.get("options");
        List<String> options = List.of();
        if (optionsRaw instanceof List<?> list) {
            options = list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        String q = question == null ? "" : String.valueOf(question);
        if (q.isBlank() && options.isEmpty()) {
            return null;
        }
        return new Clarify(q, options);
    }

    /**
     * 元数据阶段。outcome：{@code continue} / {@code error}。
     */
    public AskStageResult runMetadata(AskRequest request, AskListener listener, AskFlowState state, boolean recover) {
        BusinessContext context = state.context();
        if (context == null) {
            listener.emit("error", Map.of("message", "元数据阶段缺少本体上下文，请先连接 ontology 节点"));
            return AskStageResult.of("error", Map.of("tables", 0));
        }
        listener.emit("stage", stage("metadata", "Metadata 智能体：核对映射表和列"));
        MetadataReport report = verifier.verify(context, false);
        boolean metadataRecovered = false;
        if (recover && report.issues().stream().anyMatch(issue -> issue.startsWith("元数据中没有表"))) {
            report = verifier.verify(context, true);
            metadataRecovered = true;
        }
        listener.emit("metadata", Map.of("recovery", metadataRecovered, "report", report));
        state.setReport(report);
        state.setMetadataRecovered(metadataRecovered);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("tables", report.tables().size());
        outputs.put("report", Map.of(
                "tableCount", report.tables().size(),
                "issueCount", report.issues().size(),
                "recovered", metadataRecovered));
        if (report.tables().isEmpty()) {
            listener.emit("error", Map.of("message", "本体映射的表在元数据中都不存在：" + String.join("；", report.issues())));
            return AskStageResult.of("error", outputs);
        }
        return AskStageResult.of("continue", outputs);
    }

    /**
     * SQL 洞察阶段。outcome：{@code answered} / {@code clarify} / {@code error}。
     * {@code skills} 控制是否挂 Preference 工具；SQL 工具始终由 InsightSession 提供。
     */
    public AskStageResult runInsight(
            AskRequest request, AskListener listener, AskFlowState state, Iterable<String> skills) {
        return runInsight(request, listener, state, skills, null);
    }

    /**
     * @param systemPromptText 节点提示词（可已插值上游 {@code 本体.context} / {@code 元数据.report}）；空则只用内置 INSIGHT_PROMPT
     */
    public AskStageResult runInsight(
            AskRequest request,
            AskListener listener,
            AskFlowState state,
            Iterable<String> skills,
            String systemPromptText) {
        return runInsight(request, listener, state, skills, systemPromptText, true);
    }

    /**
     * @param emitFinalAnswer false 时只写节点出参（供 DAG 下游回答节点拼接），由编排决定是否发最终 answer
     */
    public AskStageResult runInsight(
            AskRequest request,
            AskListener listener,
            AskFlowState state,
            Iterable<String> skills,
            String systemPromptText,
            boolean emitFinalAnswer) {
        Set<String> skillSet = AskSkillIds.normalize(skills, AskSkillIds.DEFAULT_INSIGHT);
        BusinessContext context = state.context();
        MetadataReport report = state.report();
        if (context == null || report == null) {
            listener.emit("error", Map.of("message", "SQL 洞察缺少本体/元数据上下文"));
            return AskStageResult.of("error");
        }
        if (!skillSet.contains(AskSkillIds.SQL)) {
            listener.emit("error", Map.of("message", "insight 节点未启用 sql 技能，无法执行联邦查询"));
            return AskStageResult.of("error");
        }
        String question = request.question().strip();
        listener.emit("stage", stage("insight", "DataInsight 智能体：规划并执行 SQL"));
        InsightSession session = new InsightSession(question, ontologyTools, verifier, context, report,
                state.metadataRecovered(), runner, validator, listener, objectMapper, config.getResultPreviewRows());
        List<Object> tools = new ArrayList<>();
        tools.add(session);
        if (skillSet.contains(AskSkillIds.PREFERENCE) && preferenceTools != null) {
            tools.add(preferenceTools);
        }
        String system = insightSystemPrompt(systemPromptText);
        Analyst analyst = AiServices.builder(Analyst.class)
                .chatModel(llm.model())
                .tools(tools)
                .systemMessageProvider(memoryId -> systemPrompt(system))
                .maxSequentialToolsInvocations(InsightSession.MAX_SQL_ATTEMPTS + 8)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
        String raw = analyst.analyze(insightPrompt(request, session));
        AnalystReply reply = parseAnalyst(raw);
        if ("clarify".equals(reply.status())) {
            Clarify clarify = new Clarify(
                    reply.question(), reply.options() == null ? List.of() : reply.options());
            listener.emit("clarify", clarify);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("answer", mergeClarifyIntoPrimary("", clarify));
            outputs.put("sql", "");
            outputs.put("clarify", clarifyPayload(clarify));
            return AskStageResult.of("clarify", outputs);
        }
        QueryResponse result = session.resultFor(reply.sql());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", reply.answer());
        answer.put("sql", reply.sql());
        answer.put("caliber", reply.caliber() == null ? List.of() : reply.caliber());
        answer.put("steps", provenanceSteps(context, report, session, reply));
        answer.put("skills", List.copyOf(skillSet));
        long rowCount = 0;
        if (result != null) {
            answer.put("columns", result.columns());
            answer.put("rows", session.preview(result.rows()));
            answer.put("rowCount", result.rowCount());
            rowCount = result.rowCount();
        }
        // 出参完整写入变量池，供下游回答节点自由拼接；是否对外 emit 由 DAG 决定
        Map<String, Object> outputs = new LinkedHashMap<>(answer);
        outputs.put("answer", reply.answer() == null ? "" : reply.answer());
        outputs.put("sql", reply.sql() == null ? "" : reply.sql());
        outputs.put("rowCount", rowCount);
        if (emitFinalAnswer) {
            listener.emit("answer", answer);
        }
        return AskStageResult.of("answered", outputs);
    }

    /**
     * 大模型对话节点：按 skills 绑定 ontology / preference / knowledge 工具（Agent 模式）。
     * SQL 由 {@link #runInsight} 承接（FlowRunner 在勾选 sql 或旧 insight 节点时分流）。
     */
    public AskStageResult runLlmChat(
            AskRequest request,
            AskListener listener,
            String systemPromptText,
            Iterable<String> skills,
            KnowledgeTools knowledgeTools) {
        return runLlmChat(request, listener, systemPromptText, skills, knowledgeTools, true);
    }

    /**
     * @param emitFinalAnswer false 时只写节点出参；由编排在叶子或回答节点再发最终 answer
     */
    public AskStageResult runLlmChat(
            AskRequest request,
            AskListener listener,
            String systemPromptText,
            Iterable<String> skills,
            KnowledgeTools knowledgeTools,
            boolean emitFinalAnswer) {
        Set<String> skillSet = AskSkillIds.normalize(skills, AskSkillIds.DEFAULT_LLM_CHAT);
        Map<String, Object> llmStage = new LinkedHashMap<>();
        llmStage.put("name", "llm");
        llmStage.put("message", "大模型对话");
        llmStage.put("skills", List.copyOf(skillSet));
        listener.emit("stage", llmStage);
        String system = systemPromptText == null || systemPromptText.isBlank() ? "你是有帮助的中文助手。" : systemPromptText;
        String user = dialogue(request);
        List<Object> tools = new ArrayList<>();
        if (skillSet.contains(AskSkillIds.ONTOLOGY)) {
            tools.add(ontologyTools);
        }
        if (skillSet.contains(AskSkillIds.PREFERENCE) && preferenceTools != null) {
            tools.add(preferenceTools);
        }
        if (skillSet.contains(AskSkillIds.KNOWLEDGE) && knowledgeTools != null) {
            tools.add(knowledgeTools);
        }
        String answerText;
        if (tools.isEmpty()) {
            answerText = llm.complete(system, user);
        } else {
            AgentChat agent = AiServices.builder(AgentChat.class)
                    .chatModel(llm.model())
                    .tools(tools)
                    .systemMessageProvider(memoryId -> systemPrompt(system))
                    .maxSequentialToolsInvocations(12)
                    .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                    .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                    .build();
            answerText = agent.chat(user);
        }
        String raw = answerText == null ? "" : answerText.strip();
        Map<String, Object> outputs = llmChatOutputs(raw);
        Object answerOut = outputs.getOrDefault("answer", raw);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answerOut);
        payload.put("sql", outputs.get("sql"));
        payload.put("caliber", outputs.getOrDefault("caliber", List.of()));
        payload.put("steps", List.of(Map.of("agent", "llm_chat", "skills", List.copyOf(skillSet))));
        if (outputs.containsKey("canAnswer")) {
            payload.put("canAnswer", outputs.get("canAnswer"));
        }
        if (outputs.get("knowledgeId") != null) {
            payload.put("knowledgeId", outputs.get("knowledgeId"));
        }
        Clarify clarified = clarifyFrom(outputs.get("clarify"));
        if (clarified != null) {
            listener.emit("clarify", clarified);
            payload.put("clarify", clarifyPayload(clarified));
            payload.put("answer", outputs.get("answer"));
        } else if (Boolean.FALSE.equals(outputs.get("canAnswer"))) {
            // canAnswer=false：仅写出变量供 if_else，不发最终 answer
            listener.emit(
                    "stage",
                    Map.of("name", "llm", "message", "判定不能直接作答", "canAnswer", false));
        } else if (emitFinalAnswer) {
            listener.emit("answer", payload);
        }
        return AskStageResult.of(clarified != null ? "clarify" : "default", outputs);
    }

    /**
     * 通用结构化输出：回复若含 JSON 对象，则字段原样写入节点出参；
     * {@code answer} 优先取 JSON 的 answer，否则为原文。是否写出 canAnswer 等由提示词约定，无特殊模式。
     */
    Map<String, Object> llmChatOutputs(String raw) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        String text = raw == null ? "" : raw;
        outputs.put("answer", text);
        Map<String, Object> parsed = tryParseJsonMap(text);
        if (parsed == null || parsed.isEmpty()) {
            return outputs;
        }
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                outputs.put(entry.getKey(), entry.getValue());
            }
        }
        Object structuredAnswer = parsed.get("answer");
        if (structuredAnswer instanceof String s && !s.isBlank()) {
            outputs.put("answer", s.strip());
        }
        Clarify clarify = clarifyFrom(parsed.get("clarify"));
        if (clarify == null && "clarify".equals(String.valueOf(parsed.get("status")))) {
            Object question = parsed.get("question");
            Object options = parsed.get("options");
            Map<String, Object> bag = new LinkedHashMap<>();
            bag.put("question", question == null ? "" : question);
            bag.put("options", options == null ? List.of() : options);
            clarify = clarifyFrom(bag);
        }
        if (clarify != null) {
            outputs.put("answer", mergeClarifyIntoPrimary(outputs.get("answer"), clarify));
            outputs.put("clarify", clarifyPayload(clarify));
        }
        return outputs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseJsonMap(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('{') < 0) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(JsonReply.extractObject(raw), Object.class);
            if (value instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            return null;
        } catch (JacksonException | IllegalStateException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> contextSummary(BusinessContext context) {
        return contextSummary(context, null);
    }

    private static Map<String, Object> contextSummary(BusinessContext context, Clarify clarify) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", context.status());
        summary.put("confidence", context.confidence());
        summary.put(
                "concepts",
                context.concepts().stream().map(OntologyTools.ConceptView::name).limit(20).toList());
        summary.put("warnings", context.warnings());
        return mergeClarify(summary, clarify);
    }

    interface AgentChat {
        String chat(String prompt);
    }

    /** 回答步骤溯源（对齐 Auto Ontology Chat steps：Resolved / Joined / Validated）。 */
    static List<String> provenanceSteps(
            BusinessContext context,
            MetadataReport report,
            InsightSession session,
            AnalystReply reply) {
        List<String> steps = new ArrayList<>();
        if (!context.matches().isEmpty()) {
            steps.add("解析术语：" + context.matches().stream()
                    .map(hit -> hit.matchedText() + "→" + hit.name())
                    .distinct()
                    .collect(Collectors.joining("，")));
        }
        if (!context.definedConcepts().isEmpty()) {
            steps.add("复用派生口径：" + context.definedConcepts().stream()
                    .map(d -> {
                        String label = d.labelZh() == null || d.labelZh().isBlank() ? d.name() : d.labelZh();
                        if (d.sqlExpression() != null && !d.sqlExpression().isBlank()) {
                            return label + " expr=[" + d.sqlExpression() + "]";
                        }
                        return d.sqlCondition() == null || d.sqlCondition().isBlank()
                                ? label
                                : label + " [" + d.sqlCondition() + "]";
                    })
                    .collect(Collectors.joining("；")));
        }
        if (!context.paths().isEmpty()) {
            steps.add("关联路径：" + context.paths().stream()
                    .map(p -> p.from() + "→" + p.to() + "（" + p.steps().size() + " 跳）")
                    .collect(Collectors.joining("，")));
        }
        steps.add("元数据核对：" + report.tables().size() + " 张表"
                + (report.issues().isEmpty() ? "" : "，问题 " + report.issues().size() + " 项"));
        if (session.filterValidations() > 0) {
            steps.add("过滤值校验：" + session.filterValidations() + " 次");
        }
        if (reply.sql() != null && !reply.sql().isBlank()) {
            steps.add("执行 SQL 并汇总结果");
        }
        return steps;
    }

    private static QueryResponse execute(QueryService queryService, String sql) {
        QueryRequest request = new QueryRequest();
        request.setSql(sql);
        return queryService.execute(request);
    }

    /** 只检索并写出 {@code result}（Array[Object]，字段对齐知识表）；不调大模型。 */
    private Map<String, Object> retrieveKnowledge(AskRequest request, AskListener listener, int topK) {
        Map<String, Object> empty = knowledgeEmptyOutputs();
        if (knowledge == null) {
            return empty;
        }
        List<Match> matches;
        try {
            matches = knowledge.search(request.question().strip(), topK);
        } catch (RuntimeException e) {
            log.warn("知识检索失败，返回空召回: {}", e.toString());
            return empty;
        }
        if (matches.isEmpty()) {
            listener.emit("stage", stage("knowledge", "知识库：无召回"));
            listener.emit("knowledge", Map.of("matches", List.of(), "result", List.of()));
            return empty;
        }
        listener.emit("stage", stage("knowledge", "知识库：按问题召回 " + matches.size() + " 条"));
        List<Map<String, Object>> payload = matches.stream().map(AskService::toHitMap).toList();
        listener.emit("knowledge", Map.of("matches", payload, "result", payload));
        return knowledgeOutputs(payload);
    }

    private static Map<String, Object> knowledgeEmptyOutputs() {
        return new LinkedHashMap<>(Map.of("result", List.of()));
    }

    private static Map<String, Object> knowledgeOutputs(List<Map<String, Object>> payload) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("result", payload);
        return outputs;
    }

    /**
     * 召回条目：字段对齐 fed_ask_knowledge / 向量索引（id、question、answer、sql、caliber、evidence、year、version）+ score。
     */
    private static Map<String, Object> toHitMap(Match match) {
        AskKnowledge item = match.knowledge();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.id());
        row.put("score", match.score());
        row.put("question", item.question());
        row.put("answer", item.answerPair().answer());
        row.put("sql", item.answerPair().sql());
        row.put("caliber", item.answerPair().caliber() == null ? List.of() : item.answerPair().caliber());
        row.put("evidence", item.evidence() == null ? Map.of() : item.evidence());
        row.put("kind", item.kindOrDefault());
        row.put("status", item.status() == null ? "" : item.status());
        row.put("year", item.yearOrDefault());
        row.put("version", item.versionOrDefault());
        row.put("sourceRunId", item.sourceRunId() == null ? "" : item.sourceRunId());
        return row;
    }

    private ExplorerReply explore(String dialogue, BusinessContext context) {
        Explorer explorer = AiServices.builder(Explorer.class)
                .chatModel(llm.model())
                .tools(ontologyTools)
                .systemMessageProvider(memoryId -> systemPrompt(EXPLORER_PROMPT))
                .maxSequentialToolsInvocations(12)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
        String prompt = "用户问题：\n" + dialogue + "\n\n确定性检索结果（status=" + context.status()
                + ", confidence=" + context.confidence() + "）：\n"
                + "命中：" + context.matches().stream()
                        .map(hit -> hit.matchedText() + "→" + hit.name() + "(" + hit.kind() + ")")
                        .collect(Collectors.joining("，"))
                + "\n警告：" + String.join("；", context.warnings())
                + "\n未解决：" + String.join("；", context.unresolved());
        String raw = explorer.explore(prompt);
        try {
            return read(raw, ExplorerReply.class);
        } catch (IllegalStateException e) {
            log.warn("本体探索返回无法解析: {}", JsonReply.abbreviate(raw, 500));
            return new ExplorerReply(context.matches().stream().map(OntologyTools.EntityHit::name).toList(), null);
        }
    }

    /** 节点提示词（含上游变量插值）叠在内置 SQL 规则之上。 */
    static String insightSystemPrompt(String nodePrompt) {
        if (nodePrompt == null || nodePrompt.isBlank()) {
            return INSIGHT_PROMPT;
        }
        return INSIGHT_PROMPT + "\n\n【上游节点上下文】\n" + nodePrompt.strip();
    }

    private String insightPrompt(AskRequest request, InsightSession session) {
        return "当前时间：" + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + "\n\n对话：\n" + dialogue(request)
                + "\n\n业务上下文（本体）：\n" + objectMapper.writeValueAsString(session.context())
                + "\n\n元数据：\n" + objectMapper.writeValueAsString(session.report());
    }

    AnalystReply parseAnalyst(String raw) {
        try {
            return read(raw, AnalystReply.class);
        } catch (IllegalStateException e) {
            return new AnalystReply("answered", raw, null, List.of(), null, null);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return objectMapper.readerFor(type)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(JsonReply.extractObject(raw));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("无法解析大模型返回", e);
        }
    }

    static String userText(AskRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.history() != null) {
            request.history().stream()
                    .filter(turn -> turn != null && "user".equals(turn.role()) && turn.content() != null)
                    .forEach(turn -> text.append(turn.content().strip()).append('\n'));
        }
        return text.append(request.question().strip()).toString();
    }

    static String dialogue(AskRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.history() != null) {
            for (Turn turn : request.history()) {
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                text.append("user".equals(turn.role()) ? "用户：" : "助手：").append(turn.content().strip()).append('\n');
            }
        }
        return text.append("用户：").append(request.question().strip()).toString();
    }

    private static Map<String, String> stage(String name, String message) {
        return Map.of("name", name, "message", message);
    }
}
