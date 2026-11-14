package com.cmcc.cmdevops.security.federation.ask;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * 智能问数编排：Ontology → Metadata → DataInsight。
 * <p>
 * 事件：run、stage、ontology、metadata、sql、result、clarify、answer、error，最后由调用方发 done。
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

    @Autowired
    public AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            SqlGuard guard,
            QueryService queryService,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties properties,
            AskFeedbackStore feedbackStore) {
        this(ontologyTools, verifier, sql -> execute(queryService, sql), guard::validate, llm, objectMapper,
                properties.getAsk(), feedbackStore);
    }

    AskService(
            OntologyTools ontologyTools,
            MetadataVerifier verifier,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            LlmClient llm,
            ObjectMapper objectMapper,
            FederationProperties.Ask config) {
        this(ontologyTools, verifier, runner, validator, llm, objectMapper, config, new MemoryAskFeedbackStore());
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
        this.ontologyTools = ontologyTools;
        this.verifier = verifier;
        this.runner = runner;
        this.validator = validator;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.config = config;
        this.feedbackStore = feedbackStore;
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
     */
    public record AskRequest(String question, List<Turn> history, String conversationId) {

        public AskRequest(String question, List<Turn> history) {
            this(question, history, null);
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

    public void ask(AskRequest request, AskListener listener) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        llm.model();
        AskRunRecorder recorder = new AskRunRecorder(
                request, GatewayUser.current().name(), listener, System.currentTimeMillis());
        listener.emit("run", Map.of("runId", recorder.id(), "conversationId", recorder.conversationId()));
        try {
            answer(request, recorder);
        } catch (RuntimeException e) {
            recorder.failed(e);
            throw e;
        } finally {
            saveRun(recorder);
        }
    }

    /** 记录失败不影响已经给出的回答。 */
    private void saveRun(AskRunRecorder recorder) {
        try {
            feedbackStore.saveRun(recorder.toRun(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("问数记录保存失败 runId={}: {}", recorder.id(), e.toString());
        }
    }

    private void answer(AskRequest request, AskListener listener) {
        String question = request.question().strip();
        String dialogue = dialogue(request);

        listener.emit("stage", stage("ontology", "Ontology 智能体：检索业务语义"));
        BusinessContext context = ontologyTools.businessContext(userText(request), Set.of());
        AnswerabilityChecker.Answerability answerability =
                AnswerabilityChecker.check(context, ontologyTools.listConcepts(), config.getMinConfidence());
        listener.emit("answerability", answerability);

        boolean escalated = false;
        if (!OntologyTools.STATUS_OK.equals(context.status()) || context.confidence() < config.getMinConfidence()) {
            listener.emit("ontology", Map.of("mode", "deterministic", "context", context));
            listener.emit("stage", stage("ontology", "置信度 " + context.confidence() + "，转入多轮工具探索"));
            ExplorerReply reply = explore(dialogue, context);
            if (reply.clarify() != null) {
                listener.emit("clarify", reply.clarify());
                return;
            }
            context = ontologyTools.contextOf(new LinkedHashSet<>(reply.entities() == null ? List.of() : reply.entities()));
            escalated = true;
            answerability = AnswerabilityChecker.check(context, ontologyTools.listConcepts(), config.getMinConfidence());
            listener.emit("answerability", answerability);
        }
        listener.emit("ontology", Map.of("mode", escalated ? "explored" : "deterministic", "context", context));
        if (context.concepts().isEmpty()) {
            String clarifyQuestion = answerability.suggestion() == null || answerability.suggestion().isBlank()
                    ? "没有在本体中找到问题涉及的业务对象，你想分析的是哪一类？"
                    : "本体覆盖不足：" + answerability.suggestion();
            listener.emit("clarify", new Clarify(clarifyQuestion,
                    ontologyTools.listConcepts().stream().map(ConceptSummary::labelZh).limit(6).toList()));
            return;
        }

        listener.emit("stage", stage("metadata", "Metadata 智能体：核对映射表和列"));
        MetadataReport report = verifier.verify(context, false);
        boolean metadataRecovered = false;
        if (report.issues().stream().anyMatch(issue -> issue.startsWith("元数据中没有表"))) {
            report = verifier.verify(context, true);
            metadataRecovered = true;
        }
        listener.emit("metadata", Map.of("recovery", metadataRecovered, "report", report));
        if (report.tables().isEmpty()) {
            listener.emit("error", Map.of("message", "本体映射的表在元数据中都不存在：" + String.join("；", report.issues())));
            return;
        }

        listener.emit("stage", stage("insight", "DataInsight 智能体：规划并执行 SQL"));
        InsightSession session = new InsightSession(question, ontologyTools, verifier, context, report, metadataRecovered,
                runner, validator, listener, objectMapper, config.getResultPreviewRows());
        Analyst analyst = AiServices.builder(Analyst.class)
                .chatModel(llm.model())
                .tools(session)
                .systemMessageProvider(memoryId -> INSIGHT_PROMPT)
                .maxSequentialToolsInvocations(InsightSession.MAX_SQL_ATTEMPTS + 8)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();
        String raw = analyst.analyze(insightPrompt(request, session));
        AnalystReply reply = parseAnalyst(raw);
        if ("clarify".equals(reply.status())) {
            listener.emit("clarify", new Clarify(reply.question(), reply.options() == null ? List.of() : reply.options()));
            return;
        }
        QueryResponse result = session.resultFor(reply.sql());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", reply.answer());
        answer.put("sql", reply.sql());
        answer.put("caliber", reply.caliber() == null ? List.of() : reply.caliber());
        answer.put("steps", provenanceSteps(context, report, session, reply));
        if (result != null) {
            answer.put("columns", result.columns());
            answer.put("rows", session.preview(result.rows()));
            answer.put("rowCount", result.rowCount());
        }
        listener.emit("answer", answer);
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

    private ExplorerReply explore(String dialogue, BusinessContext context) {
        Explorer explorer = AiServices.builder(Explorer.class)
                .chatModel(llm.model())
                .tools(ontologyTools)
                .systemMessageProvider(memoryId -> EXPLORER_PROMPT)
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
