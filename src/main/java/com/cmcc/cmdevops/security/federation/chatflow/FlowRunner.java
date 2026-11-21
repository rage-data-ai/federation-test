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
 * 按发布图执行节点（Dify 式白盒 + 变量入参/出参）：
 * <ul>
 *   <li>运行前灌入 {@code sys.*}；每节点解析 {@code config.inputs}，写出 {@code nodeId.*}</li>
 *   <li>阶段节点真实拆跑并按 outcome 选边</li>
 *   <li>{@code llm_chat} 支持提示词变量插值与工具技能</li>
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

    /** 编排预览：同一套白盒执行，发 node_start/finish，不落正式 run。 */
    public void runPreview(PublishedApp app, AskRequest request, AskListener listener) {
        run(app, request, listener, true);
    }

    private void run(PublishedApp app, AskRequest request, AskListener listener, boolean preview) {
        FlowGraph graph = FlowGraphMigrations.normalize(app.graph());
        graph.validate();
        assertCompatibleAskNodes(graph);
        AskRequest scoped = withHistoryWindow(graph, withAppMeta(request, app));
        FlowVariablePool pool = FlowVariablePool.fromRequest(scoped, GatewayUser.current().name());

        boolean stageGraph = graph.nodes().stream().anyMatch(node -> DefaultFlows.isAskStageType(node.type()));
        if (stageGraph) {
            runWhiteBox(graph, scoped, listener, pool, preview);
            return;
        }

        runSimple(graph, scoped, listener, pool, preview, app.appId());
    }

    private void runSimple(
            FlowGraph graph,
            AskRequest scoped,
            AskListener listener,
            FlowVariablePool pool,
            boolean preview,
            String appId) {
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
            FlowNode node = graph.node(nodeId);
            Map<String, Object> inputs = resolveNodeInputs(node, pool);
            emitNodeStart(listener, node);
            try {
                switch (node.type()) {
                    case "start" -> {
                        lastOutcome = "default";
                        Map<String, Object> outs = Map.of(
                                "query", pool.sys().get("query"),
                                "history", pool.sys().get("history"),
                                "conversation_id", pool.sys().get("conversation_id"));
                        pool.putNode(node, outs);
                        emitVars(listener, node.id(), outs, pool);
                    }
                    case "end" -> {
                        emitNodeFinish(listener, node, "default", null);
                        return;
                    }
                    case "builtin_ask" -> throw new IllegalArgumentException(
                            "builtin_ask 已移除，请发布白盒问数图（knowledge/ontology/metadata/llm_chat）");
                    case "if_else" -> {
                        AskStageResult result = evaluateIfElse(inputs);
                        lastOutcome = result.outcome();
                        pool.putNode(node, result.outputs());
                        emitVars(listener, node.id(), result.outputs(), pool);
                    }
                    case "llm_chat", "insight" -> {
                        AskRunRecorder recorder = askService.beginRun(scoped, listener, preview);
                        try {
                            AskStageResult result =
                                    runResolvedLlm(node, scoped, recorder, inputs, pool, new AskFlowState());
                            lastOutcome = result.outcome();
                            pool.putNode(node, result.outputs());
                            emitVars(listener, node.id(), result.outputs(), pool);
                        } catch (RuntimeException e) {
                            emitNodeFinish(listener, node, "error", e.getMessage());
                            if (preview) {
                                askService.failAndDiscard(recorder, e);
                            } else {
                                askService.failAndFinish(recorder, e);
                            }
                            throw e;
                        }
                        if (preview) {
                            askService.discardRun(recorder);
                        } else {
                            askService.finishRun(recorder);
                        }
                    }
                    case "answer" -> {
                        AskRunRecorder recorder = askService.beginRun(scoped, listener, preview);
                        try {
                            AskStageResult result = runAnswerNode(node, inputs, pool, recorder);
                            lastOutcome = result.outcome();
                            pool.putNode(node, result.outputs());
                            emitVars(listener, node.id(), result.outputs(), pool);
                        } catch (RuntimeException e) {
                            emitNodeFinish(listener, node, "error", e.getMessage());
                            if (preview) {
                                askService.failAndDiscard(recorder, e);
                            } else {
                                askService.failAndFinish(recorder, e);
                            }
                            throw e;
                        }
                        if (preview) {
                            askService.discardRun(recorder);
                        } else {
                            askService.finishRun(recorder);
                        }
                    }
                    default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
                }
                emitNodeFinish(listener, node, lastOutcome, null);
            } catch (RuntimeException e) {
                if (!"end".equals(node.type())) {
                    // node_finish already emitted for llm/answer errors
                }
                throw e;
            }
            String next = graph.next(nodeId, lastOutcome);
            if (next == null) {
                return;
            }
            nodeId = next;
        }
        log.warn("流程跳转超过 {} 次，已停止 appId={}", MAX_HOPS, appId);
    }

    private void runWhiteBox(
            FlowGraph graph, AskRequest scoped, AskListener listener, FlowVariablePool pool, boolean preview) {
        AskRunRecorder recorder = askService.beginRun(scoped, listener, preview);
        AskFlowState state = new AskFlowState();
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        try {
            for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
                FlowNode node = graph.node(nodeId);
                Map<String, Object> inputs = resolveNodeInputs(node, pool);
                AskRequest nodeRequest = requestWithQuery(scoped, inputs);
                emitNodeStart(listener, node);
                AskStageResult result;
                try {
                    switch (node.type()) {
                        case "start" -> {
                            lastOutcome = "default";
                            Map<String, Object> outs = Map.of(
                                    "query", pool.sys().get("query"),
                                    "history", pool.sys().get("history"),
                                    "conversation_id", pool.sys().get("conversation_id"));
                            pool.putNode(node, outs);
                            emitVars(listener, node.id(), outs, pool);
                            result = AskStageResult.of("default", outs);
                        }
                        case "end" -> {
                            emitNodeFinish(listener, node, "default", null);
                            return;
                        }
                        case "knowledge" -> {
                            int topK = FlowVarRefs.asInt(inputs, "topK", AskKnowledgeService.DEFAULT_TOP_K);
                            result = askService.runKnowledge(nodeRequest, recorder, state, topK);
                        }
                        case "if_else" -> result = runIfElse(node, inputs, pool, recorder);
                        case "ontology" -> {
                            result = askService.runOntology(
                                    nodeRequest, recorder, state, AskSkillIds.DEFAULT_ONTOLOGY);
                            state.markOntologyPrepared();
                        }
                        case "metadata" -> {
                            result = askService.runMetadata(
                                    nodeRequest, recorder, state, FlowVarRefs.asBool(inputs, "recover", true));
                            state.markMetadataPrepared();
                        }
                        case "insight", "llm_chat" ->
                                result = runResolvedLlm(node, nodeRequest, recorder, inputs, pool, state);
                        case "answer" -> result = runAnswerNode(node, inputs, pool, recorder);
                        case "builtin_ask" -> throw new IllegalArgumentException(
                                "builtin_ask 已移除，请使用 knowledge/ontology/metadata/llm_chat 白盒图");
                        default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
                    }
                } catch (RuntimeException e) {
                    emitNodeFinish(listener, node, "error", e.getMessage());
                    throw e;
                }
                if (result != null) {
                    lastOutcome = result.outcome();
                    pool.putNode(node, result.outputs());
                    emitVars(listener, node.id(), result.outputs(), pool);
                    emitNodeFinish(listener, node, lastOutcome, null);
                }
                String next = graph.next(nodeId, lastOutcome);
                if (next == null) {
                    if (!"answered".equals(lastOutcome)
                            && !"clarify".equals(lastOutcome)
                            && !"error".equals(lastOutcome)
                            && !"default".equals(lastOutcome)
                            && !"true".equals(lastOutcome)
                            && !"false".equals(lastOutcome)
                            && !"continue".equals(lastOutcome)) {
                        listener.emit(
                                "error",
                                Map.of("message", "流程在节点 " + nodeId + " 后没有出口（outcome=" + lastOutcome + ")"));
                    }
                    return;
                }
                nodeId = next;
            }
            log.warn("流程跳转超过 {} 次，已停止", MAX_HOPS);
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

    private static void emitNodeStart(AskListener listener, FlowNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("title", node.title());
        payload.put("type", node.type());
        listener.emit("node_start", payload);
    }

    private static void emitNodeFinish(AskListener listener, FlowNode node, String outcome, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("title", node.title());
        payload.put("type", node.type());
        payload.put("outcome", outcome == null ? "default" : outcome);
        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }
        listener.emit("node_finish", payload);
    }

    private AskStageResult runIfElse(
            FlowNode node, Map<String, Object> inputs, FlowVariablePool pool, AskListener listener) {
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
     * 回答节点：固定文案 + 上游变量拼接。缺失 / null 变量按空串拼入，不抛错、不中断。
     * 文案取自 {@code config.inputs.text} 或顶层 {@code config.text}（二者等价）。
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
        payload.put("sql", null);
        payload.put("caliber", List.of());
        payload.put("steps", List.of(Map.of("agent", "answer")));
        listener.emit("answer", payload);
        return AskStageResult.of("default", Map.of("answer", text));
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
            // 已解析值：null 变量插值后可能是 ""；纯缺失引用可能仍是 null（上面已排除）
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
        if (skillSet.contains(AskSkillIds.SQL) || insightAlias) {
            ensureInsightContext(forLlm, listener, state);
            return askService.runInsight(
                    forLlm,
                    listener,
                    state,
                    insightAlias && skillSet.isEmpty() ? AskSkillIds.DEFAULT_INSIGHT : skillSet,
                    system);
        }
        return askService.runLlmChat(forLlm, listener, system, skillSet, knowledgeTools);
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
        if (!target.containsKey(key) && source.containsKey(key)) {
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
