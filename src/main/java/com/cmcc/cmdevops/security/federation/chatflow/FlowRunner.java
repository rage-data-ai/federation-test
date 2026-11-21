package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.AskFlowState;
import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.AskSkillIds;
import com.cmcc.cmdevops.security.federation.ask.AskStageResult;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/**
 * 按发布图自由组装执行（Dify 式白盒 DAG）：
 * <ul>
 *   <li>正式问数与编排预览共用同一 walker（仅图来源 / 是否落库不同）</li>
 *   <li>任意合法边连接的节点序列均可跑；阶段节点按 outcome 选边</li>
 *   <li>每节点 SSE：{@code node_start}（含解析后入参）、{@code vars}、{@code node_finish}（含耗时与出参）</li>
 *   <li>最终用户可见回答只由 {@code answer} 节点发出</li>
 * </ul>
 */
@Component
public class FlowRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowRunner.class);
    private static final int MAX_HOPS = 32;

    private final AskService askService;
    private final KnowledgeTools knowledgeTools;
    private final FederationProperties properties;

    public FlowRunner(AskService askService, KnowledgeTools knowledgeTools, FederationProperties properties) {
        this.askService = askService;
        this.knowledgeTools = knowledgeTools;
        this.properties = properties;
    }

    public void run(PublishedApp app, AskRequest request, AskListener listener) {
        run(app, request, listener, false);
    }

    /** 编排预览：同一套 DAG 执行，发 node_start/finish，不落正式 run。 */
    public void runPreview(PublishedApp app, AskRequest request, AskListener listener) {
        run(app, request, listener, true);
    }

    private void run(PublishedApp app, AskRequest request, AskListener listener, boolean preview) {
        FlowGraph graph = FlowGraphMigrations.normalize(app.graph());
        graph.validate();
        assertCompatibleAskNodes(graph);
        AskRequest scoped = withHistoryWindow(graph, withAppMeta(request, app));
        FlowVariablePool pool = FlowVariablePool.fromRequest(scoped, GatewayUser.current().name());
        runDag(graph, scoped, listener, pool, preview, app.appId());
    }

    /**
     * 统一 DAG walker：自由组装的任意合法图走同一路径（单次 beginRun，逐节点详情）。
     */
    private void runDag(
            FlowGraph graph,
            AskRequest scoped,
            AskListener listener,
            FlowVariablePool pool,
            boolean preview,
            String appId) {
        AskRunRecorder recorder = askService.beginRun(scoped, listener, preview);
        AskFlowState state = new AskFlowState();
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        boolean emittedUserExit = false;
        try {
            for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
                FlowNode node = graph.node(nodeId);
                Map<String, Object> inputs = resolveNodeInputs(node, pool);
                AskRequest nodeRequest = requestWithQuery(scoped, inputs);
                long startedAt = System.nanoTime();
                emitNodeStart(listener, node, inputs);
                AskStageResult result;
                try {
                    result = executeNode(node, nodeRequest, recorder, inputs, pool, state);
                } catch (RuntimeException e) {
                    emitNodeFinish(listener, node, "error", e.getMessage(), elapsedMs(startedAt), Map.of());
                    throw e;
                }
                if ("end".equals(node.type())) {
                    emitNodeFinish(listener, node, "default", null, elapsedMs(startedAt), Map.of());
                    if (!emittedUserExit) {
                        listener.emit("error", Map.of("message", "流程结束但未发出回答（缺少回答节点出口）"));
                    }
                    return;
                }
                if ("answer".equals(node.type())) {
                    emittedUserExit = true;
                }
                lastOutcome = result.outcome();
                if ("clarify".equals(lastOutcome) || "answered".equals(lastOutcome)) {
                    // clarify/answered 可能由 llm 直接发出；仍要求最终经 answer，但至少不算静默
                    if ("clarify".equals(lastOutcome)) {
                        emittedUserExit = true;
                    }
                }
                Map<String, Object> outputs = result.outputs() == null ? Map.of() : result.outputs();
                pool.putNode(node, outputs);
                emitVars(listener, node.id(), outputs, pool);
                emitNodeFinish(listener, node, lastOutcome, null, elapsedMs(startedAt), outputs);
                String next = graph.next(nodeId, lastOutcome);
                if (next == null) {
                    if (!emittedUserExit) {
                        listener.emit(
                                "error",
                                Map.of(
                                        "message",
                                        "流程在节点 "
                                                + nodeId
                                                + " 结束后未发出回答（outcome="
                                                + lastOutcome
                                                + "），请将边接到回答节点"));
                    } else if (!isTerminalOutcome(lastOutcome)) {
                        listener.emit(
                                "error",
                                Map.of(
                                        "message",
                                        "流程在节点 " + nodeId + " 后没有出口（outcome=" + lastOutcome + ")"));
                    }
                    return;
                }
                nodeId = next;
            }
            log.warn("流程跳转超过 {} 次，已停止 appId={}", MAX_HOPS, appId);
            listener.emit("error", Map.of("message", "流程跳转超过上限，已停止"));
        } catch (RuntimeException e) {
            if (preview) {
                askService.failAndDiscard(recorder, e);
            } else {
                recorder.failed(e);
            }
            throw e;
        } finally {
            if (preview) {
                askService.discardRun(recorder);
            } else {
                askService.finishRun(recorder);
            }
        }
    }

    private AskStageResult executeNode(
            FlowNode node,
            AskRequest nodeRequest,
            AskListener recorder,
            Map<String, Object> inputs,
            FlowVariablePool pool,
            AskFlowState state) {
        return switch (node.type()) {
            case "start" -> {
                Map<String, Object> outs = new LinkedHashMap<>();
                outs.put("query", pool.sys().get("query"));
                outs.put("history", pool.sys().get("history"));
                outs.put("conversation_id", pool.sys().get("conversation_id"));
                yield AskStageResult.of("default", outs);
            }
            case "end" -> {
                // 旧图兼容：归一化后极少出现；当作叶子出口
                yield AskStageResult.of("default", Map.of());
            }
            case "knowledge" -> {
                int topK = FlowVarRefs.asInt(inputs, "topK", AskKnowledgeService.DEFAULT_TOP_K);
                yield askService.runKnowledge(nodeRequest, recorder, state, topK);
            }
            case "if_else" -> runIfElse(node, inputs, recorder);
            case "ontology" -> {
                AskStageResult result =
                        askService.runOntology(nodeRequest, recorder, state, AskSkillIds.DEFAULT_ONTOLOGY);
                state.markOntologyPrepared();
                yield result;
            }
            case "metadata" -> {
                AskStageResult result = askService.runMetadata(
                        nodeRequest, recorder, state, FlowVarRefs.asBool(inputs, "recover", true));
                state.markMetadataPrepared();
                yield result;
            }
            case "insight", "llm_chat" ->
                    // 上游只写池；最终 answer 必须由回答节点发出
                    runResolvedLlm(node, nodeRequest, recorder, inputs, pool, state);
            case "answer" -> runAnswerNode(node, inputs, pool, recorder);
            case "builtin_ask" -> throw new IllegalArgumentException(
                    "builtin_ask 已移除，请使用 knowledge/ontology/metadata/llm_chat 白盒图");
            default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
        };
    }

    private static boolean isTerminalOutcome(String outcome) {
        return "answered".equals(outcome)
                || "clarify".equals(outcome)
                || "error".equals(outcome)
                || "default".equals(outcome)
                || "true".equals(outcome)
                || "false".equals(outcome)
                || "continue".equals(outcome);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static void emitNodeStart(AskListener listener, FlowNode node, Map<String, Object> inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("title", node.title());
        payload.put("type", node.type());
        payload.put("inputs", inputs == null ? Map.of() : inputs);
        listener.emit("node_start", payload);
    }

    private static void emitNodeFinish(
            AskListener listener,
            FlowNode node,
            String outcome,
            String error,
            long durationMs,
            Map<String, Object> outputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("title", node.title());
        payload.put("type", node.type());
        payload.put("outcome", outcome == null ? "default" : outcome);
        payload.put("durationMs", durationMs);
        payload.put("outputs", outputs == null ? Map.of() : outputs);
        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }
        listener.emit("node_finish", payload);
    }

    private AskStageResult runIfElse(FlowNode node, Map<String, Object> inputs, AskListener listener) {
        AskStageResult result = evaluateIfElse(inputs);
        listener.emit(
                "stage",
                Map.of(
                        "name",
                        "if_else",
                        "message",
                        "条件 " + node.title() + " → " + result.outcome(),
                        "outcome",
                        result.outcome()));
        return result;
    }

    static AskStageResult evaluateIfElse(Map<String, Object> inputs) {
        Object left = inputs.get("left");
        String op = FlowVarRefs.asString(inputs, "op", ">");
        Object right = inputs.get("right");
        boolean ok = FlowVarRefs.compare(left, op, right);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("result", ok);
        if (left != null) {
            outputs.put("left", left);
        }
        outputs.put("op", op);
        if (right != null) {
            outputs.put("right", right);
        }
        return AskStageResult.of(ok ? "true" : "false", outputs);
    }

    /**
     * 回答节点：固定文案 + 上游变量拼接（DAG 对话出口）。缺失 / null 变量按空串拼入，不抛错、不中断。
     * 文案取自 {@code config.inputs.text} 或顶层 {@code config.text}（二者等价）。
     * 若模板引用的上游节点出参含 sql/rows/caliber 等，一并并入最终 answer 事件。
     */
    static AskStageResult runAnswerNode(
            FlowNode node, Map<String, Object> inputs, FlowVariablePool pool, AskListener listener) {
        String template = answerTextTemplate(node, inputs);
        String text = FlowVarRefs.interpolate(template, pool);
        if (text == null) {
            text = "";
        }
        listener.emit("stage", Map.of("name", "answer", "message", "直接输出回答"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", text);
        enrichAnswerFromUpstream(payload, template, pool);
        payload.putIfAbsent("sql", null);
        payload.putIfAbsent("caliber", List.of());
        payload.putIfAbsent("steps", List.of(Map.of("agent", "answer")));
        listener.emit("answer", payload);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("answer", text);
        copyIfPresent(outputs, payload, "sql");
        copyIfPresent(outputs, payload, "rows");
        copyIfPresent(outputs, payload, "columns");
        copyIfPresent(outputs, payload, "rowCount");
        copyIfPresent(outputs, payload, "caliber");
        return AskStageResult.of("default", outputs);
    }

    /** 从模板里出现的 {@code {{#节点名.字段#}}} 合并上游富字段（sql/rows 等）。 */
    static void enrichAnswerFromUpstream(Map<String, Object> payload, String template, FlowVariablePool pool) {
        if (payload == null || pool == null || template == null || template.isEmpty()) {
            return;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{#([^.}]+)(\\.[^#]+)?#\\}\\}")
                .matcher(template);
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String title = matcher.group(1);
            if (title != null && !title.isBlank() && !"sys".equals(title)) {
                titles.add(title.strip());
            }
        }
        for (String title : titles) {
            Map<String, Object> ups = pool.node(title);
            if (ups == null || ups.isEmpty()) {
                continue;
            }
            copyIfAbsent(payload, ups, "sql");
            copyIfAbsent(payload, ups, "rows");
            copyIfAbsent(payload, ups, "columns");
            copyIfAbsent(payload, ups, "rowCount");
            copyIfAbsent(payload, ups, "caliber");
            copyIfAbsent(payload, ups, "steps");
            copyIfAbsent(payload, ups, "knowledgeId");
            copyIfAbsent(payload, ups, "canAnswer");
        }
    }

    private static void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    /**
     * 读取用户配置的回答模板。优先未解析的原始模板，避免纯 {@code {{#missing#}}} 被
     * resolveInputs 收成 null 后误回退到 sys.query。
     */
    @SuppressWarnings("unchecked")
    static String answerTextTemplate(FlowNode node, Map<String, Object> inputs) {
        Object raw = null;
        if (node != null && node.config() != null) {
            Object nested = node.config().get("inputs");
            if (nested instanceof Map<?, ?> map && map.get("text") != null) {
                raw = map.get("text");
            }
            if (raw == null) {
                raw = node.config().get("text");
            }
        }
        if (raw == null && inputs != null && inputs.get("text") != null) {
            raw = inputs.get("text");
        }
        if (raw == null) {
            return "{{#sys.query#}}";
        }
        return String.valueOf(raw);
    }

    /**
     * 统一大模型节点：勾选 {@code sql} 时走 InsightSession（原 SQL 洞察）；否则为通用对话 Agent。
     * 旧图 {@code insight} 类型视为已勾选 sql/preference。
     */
    private AskStageResult runResolvedLlm(
            FlowNode node,
            AskRequest scoped,
            AskListener listener,
            Map<String, Object> inputs,
            FlowVariablePool pool,
            AskFlowState state) {
        String system = FlowVarRefs.asString(
                inputs,
                "systemPrompt",
                stringConfig(node, "systemPrompt", "你是有帮助的中文助手。"));
        system = FlowVarRefs.interpolate(system, pool);
        Object skillsRaw = inputs.containsKey("skills")
                ? inputs.get("skills")
                : node.config().get("skills");
        boolean insightAlias = "insight".equals(node.type());
        var skillSet = AskSkillIds.normalize(
                skillsRaw, insightAlias ? AskSkillIds.DEFAULT_INSIGHT : AskSkillIds.DEFAULT_LLM_CHAT);
        AskRequest withQuery = requestWithQuery(scoped, inputs);
        int llmWindow = FlowVarRefs.asInt(inputs, "memoryWindowTurns", -1);
        if (llmWindow < 0 && node.config().get("memoryWindowTurns") instanceof Number number) {
            llmWindow = number.intValue();
        }
        AskRequest forLlm = llmWindow >= 0 ? trimHistory(withQuery, llmWindow) : withQuery;
        // 正式问数与预览共用：永不在此抢发最终 answer，一律交给回答节点
        if (skillSet.contains(AskSkillIds.SQL) || insightAlias) {
            ensureInsightContext(forLlm, listener, state);
            return askService.runInsight(
                    forLlm,
                    listener,
                    state,
                    insightAlias && skillSet.isEmpty() ? AskSkillIds.DEFAULT_INSIGHT : skillSet,
                    system,
                    false);
        }
        return askService.runLlmChat(forLlm, listener, system, skillSet, knowledgeTools, false);
    }

    /** 缺本体/元数据且上游未跑过对应节点时补跑，使单独的「大模型+SQL」也能执行。 */
    private void ensureInsightContext(AskRequest request, AskListener listener, AskFlowState state) {
        if (!state.ontologyPrepared() && state.context() == null) {
            askService.runOntology(request, listener, state, AskSkillIds.DEFAULT_ONTOLOGY);
            state.markOntologyPrepared();
        }
        if (!state.metadataPrepared() && state.report() == null) {
            askService.runMetadata(request, listener, state, true);
            state.markMetadataPrepared();
        }
    }

    private static AskRequest trimHistory(AskRequest request, int window) {
        List<Turn> history = request.history() == null ? List.of() : request.history();
        if (window <= 0) {
            return new AskRequest(request.question(), List.of(), request.conversationId(), request.appId());
        }
        if (history.size() <= window) {
            return request;
        }
        return new AskRequest(
                request.question(),
                new ArrayList<>(history.subList(history.size() - window, history.size())),
                request.conversationId(),
                request.appId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveNodeInputs(FlowNode node, FlowVariablePool pool) {
        FlowNodePorts.Spec spec = FlowNodePorts.spec(node.type());
        Map<String, Object> configured = new LinkedHashMap<>();
        Object rawInputs = node.config().get("inputs");
        if (rawInputs instanceof Map<?, ?> map) {
            configured.putAll((Map<String, Object>) map);
        }
        // 兼容旧图：顶层 topK / skills / systemPrompt / recover 视作未显式绑定时的字面量
        copyIfAbsent(configured, node.config(), "topK");
        copyIfAbsent(configured, node.config(), "skills");
        copyIfAbsent(configured, node.config(), "systemPrompt");
        copyIfAbsent(configured, node.config(), "recover");
        copyIfAbsent(configured, node.config(), "memoryWindowTurns");
        copyIfAbsent(configured, node.config(), "result");
        copyIfAbsent(configured, node.config(), "left");
        copyIfAbsent(configured, node.config(), "op");
        copyIfAbsent(configured, node.config(), "right");
        // answer 模板：顶层 config.text 与 inputs.text 等价（调色板新建曾只写顶层）
        copyIfAbsent(configured, node.config(), "text");
        return FlowVarRefs.resolveInputs(spec.defaultInputs(), configured, pool);
    }

    private static void copyIfAbsent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || target.containsKey(key)) {
            return;
        }
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private static AskRequest requestWithQuery(AskRequest scoped, Map<String, Object> inputs) {
        String query = FlowVarRefs.asString(inputs, "query", scoped.question());
        if (query.equals(scoped.question())) {
            return scoped;
        }
        return new AskRequest(query, scoped.history(), scoped.conversationId(), scoped.appId());
    }

    private static void emitVars(
            AskListener listener, String nodeId, Map<String, Object> outputs, FlowVariablePool pool) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("outputs", outputs);
        payload.put("paths", pool.listPaths());
        listener.emit("vars", payload);
    }

    static void assertCompatibleAskNodes(FlowGraph graph) {
        boolean hasBuiltin = graph.nodes().stream().anyMatch(node -> "builtin_ask".equals(node.type()));
        if (hasBuiltin) {
            throw new IllegalArgumentException(
                    "builtin_ask 已移除，请迁移为 knowledge → llm_chat → if_else → ontology → metadata → llm_chat(sql)");
        }
    }

    private static AskRequest withAppMeta(AskRequest request, PublishedApp app) {
        return new AskRequest(request.question(), request.history(), request.conversationId(), app.appId());
    }

    AskRequest withHistoryWindow(FlowGraph graph, AskRequest request) {
        int window = properties.getAsk().getHistoryWindowTurns();
        try {
            FlowNode start = graph.node(graph.startNodeId());
            Object raw = start.config().get("memoryWindowTurns");
            if (raw instanceof Number number) {
                window = Math.max(0, number.intValue());
            } else if (raw instanceof String text && !text.isBlank()) {
                window = Math.max(0, Integer.parseInt(text.strip()));
            }
        } catch (RuntimeException ignored) {
            // 保持全局默认
        }
        List<Turn> history = request.history() == null ? List.of() : request.history();
        if (window <= 0 || history.size() <= window) {
            return request;
        }
        List<Turn> trimmed = new ArrayList<>(history.subList(history.size() - window, history.size()));
        return new AskRequest(request.question(), trimmed, request.conversationId(), request.appId());
    }

    private static String stringConfig(FlowNode node, String key, String fallback) {
        Object value = node.config().get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }
}
