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
    private final FederationProperties properties;

    public FlowRunner(AskService askService, FederationProperties properties) {
        this.askService = askService;
        this.properties = properties;
    }

    public void run(PublishedApp app, AskRequest request, AskListener listener) {
        FlowGraph graph = app.graph();
        graph.validate();
        assertCompatibleAskNodes(graph);
        AskRequest scoped = withHistoryWindow(graph, withAppMeta(request, app));
        FlowVariablePool pool = FlowVariablePool.fromRequest(scoped, GatewayUser.current().name());

        boolean stageGraph = graph.nodes().stream().anyMatch(node -> DefaultFlows.isAskStageType(node.type()));
        if (stageGraph) {
            runWhiteBox(graph, scoped, listener, pool);
            return;
        }

        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
            FlowNode node = graph.node(nodeId);
            Map<String, Object> inputs = resolveNodeInputs(node, pool);
            switch (node.type()) {
                case "start" -> {
                    lastOutcome = "default";
                    pool.putNode(node.id(), Map.of(
                            "query", pool.sys().get("query"),
                            "history", pool.sys().get("history"),
                            "conversation_id", pool.sys().get("conversation_id")));
                }
                case "end" -> {
                    return;
                }
                case "builtin_ask" -> {
                    askService.ask(requestWithQuery(scoped, inputs), listener);
                    lastOutcome = "default";
                    pool.putNode(node.id(), Map.of("outcome", lastOutcome));
                }
                case "llm_chat" -> {
                    AskRunRecorder recorder = askService.beginRun(scoped, listener);
                    try {
                        AskStageResult result = runResolvedLlm(node, scoped, recorder, inputs, pool);
                        lastOutcome = result.outcome();
                        pool.putNode(node.id(), result.outputs());
                    } catch (RuntimeException e) {
                        askService.failAndFinish(recorder, e);
                        throw e;
                    }
                    askService.finishRun(recorder);
                }
                case "answer" -> {
                    AskRunRecorder recorder = askService.beginRun(scoped, listener);
                    try {
                        AskStageResult result = runAnswerNode(inputs, pool, recorder);
                        lastOutcome = result.outcome();
                        pool.putNode(node.id(), result.outputs());
                    } catch (RuntimeException e) {
                        askService.failAndFinish(recorder, e);
                        throw e;
                    }
                    askService.finishRun(recorder);
                }
                default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
            }
            String next = graph.next(nodeId, lastOutcome);
            if (next == null) {
                return;
            }
            nodeId = next;
        }
        log.warn("流程跳转超过 {} 次，已停止 appId={}", MAX_HOPS, app.appId());
    }

    private void runWhiteBox(FlowGraph graph, AskRequest scoped, AskListener listener, FlowVariablePool pool) {
        AskRunRecorder recorder = askService.beginRun(scoped, listener);
        AskFlowState state = new AskFlowState();
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        try {
            for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
                FlowNode node = graph.node(nodeId);
                Map<String, Object> inputs = resolveNodeInputs(node, pool);
                AskRequest nodeRequest = requestWithQuery(scoped, inputs);
                AskStageResult result;
                switch (node.type()) {
                    case "start" -> {
                        lastOutcome = "default";
                        pool.putNode(node.id(), Map.of(
                                "query", pool.sys().get("query"),
                                "history", pool.sys().get("history"),
                                "conversation_id", pool.sys().get("conversation_id")));
                        result = null;
                    }
                    case "end" -> {
                        return;
                    }
                    case "knowledge" -> {
                        int topK = FlowVarRefs.asInt(inputs, "topK", AskKnowledgeService.DEFAULT_TOP_K);
                        result = askService.runKnowledge(nodeRequest, recorder, state, topK);
                    }
                    case "ontology" -> result = askService.runOntology(
                            nodeRequest,
                            recorder,
                            state,
                            AskSkillIds.normalize(
                                    FlowVarRefs.asSkills(inputs, "skills", AskSkillIds.DEFAULT_ONTOLOGY),
                                    AskSkillIds.DEFAULT_ONTOLOGY));
                    case "metadata" -> result = askService.runMetadata(
                            nodeRequest, recorder, state, FlowVarRefs.asBool(inputs, "recover", true));
                    case "insight" -> result = askService.runInsight(
                            nodeRequest,
                            recorder,
                            state,
                            AskSkillIds.normalize(
                                    FlowVarRefs.asSkills(inputs, "skills", AskSkillIds.DEFAULT_INSIGHT),
                                    AskSkillIds.DEFAULT_INSIGHT));
                    case "llm_chat" -> result = runResolvedLlm(node, nodeRequest, recorder, inputs, pool);
                    case "answer" -> result = runAnswerNode(inputs, pool, recorder);
                    case "builtin_ask" -> throw new IllegalArgumentException("白盒阶段图不应包含 builtin_ask");
                    default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
                }
                if (result != null) {
                    lastOutcome = result.outcome();
                    pool.putNode(node.id(), result.outputs());
                    emitVars(listener, node.id(), result.outputs(), pool);
                }
                String next = graph.next(nodeId, lastOutcome);
                if (next == null) {
                    if (!"answered".equals(lastOutcome)
                            && !"clarify".equals(lastOutcome)
                            && !"error".equals(lastOutcome)
                            && !"default".equals(lastOutcome)) {
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
            recorder.failed(e);
            throw e;
        } finally {
            askService.finishRun(recorder);
        }
    }

    private static AskStageResult runAnswerNode(
            Map<String, Object> inputs, FlowVariablePool pool, AskListener listener) {
        String template = FlowVarRefs.asString(inputs, "text", "{{#sys.query#}}");
        String text = FlowVarRefs.interpolate(template, pool);
        listener.emit("stage", Map.of("name", "answer", "message", "直接输出回答"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", text);
        payload.put("sql", null);
        payload.put("caliber", List.of());
        payload.put("steps", List.of(Map.of("agent", "answer")));
        listener.emit("answer", payload);
        return AskStageResult.of("default", Map.of("answer", text));
    }

    private AskStageResult runResolvedLlm(
            FlowNode node,
            AskRequest scoped,
            AskListener listener,
            Map<String, Object> inputs,
            FlowVariablePool pool) {
        String system = FlowVarRefs.asString(
                inputs,
                "systemPrompt",
                stringConfig(node, "systemPrompt", "你是有帮助的中文助手。"));
        system = FlowVarRefs.interpolate(system, pool);
        Object skillsRaw = inputs.containsKey("skills")
                ? inputs.get("skills")
                : node.config().get("skills");
        AskRequest withQuery = requestWithQuery(scoped, inputs);
        // llm_chat 可单独覆盖记忆窗口（优先于开始节点已截断的 history）
        int llmWindow = FlowVarRefs.asInt(inputs, "memoryWindowTurns", -1);
        if (llmWindow < 0 && node.config().get("memoryWindowTurns") instanceof Number number) {
            llmWindow = number.intValue();
        }
        AskRequest forLlm = llmWindow >= 0 ? trimHistory(withQuery, llmWindow) : withQuery;
        return askService.runLlmChat(forLlm, listener, system, AskSkillIds.forLlmChat(skillsRaw));
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
        boolean hasStages = graph.nodes().stream().anyMatch(node -> DefaultFlows.isAskStageType(node.type()));
        if (hasBuiltin && hasStages) {
            throw new IllegalArgumentException("流程不能同时包含 builtin_ask 与 knowledge/ontology/metadata/insight 阶段节点");
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
