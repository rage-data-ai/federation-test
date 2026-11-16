package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
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
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeTools;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 按发布图执行节点（Dify 式白盒）：
 * <ul>
 *   <li>{@code knowledge}/{@code ontology}/{@code metadata}/{@code insight}：真实拆跑，按 outcome 选边</li>
 *   <li>{@code builtin_ask}：整段 {@link AskService#ask}</li>
 *   <li>{@code llm_chat}：可按 config.skills 绑定 ontology / preference / knowledge 工具</li>
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
        FlowGraph graph = app.graph();
        graph.validate();
        assertCompatibleAskNodes(graph);
        AskRequest scoped = withHistoryWindow(graph, withAppMeta(request, app));

        boolean stageGraph = graph.nodes().stream().anyMatch(node -> DefaultFlows.isAskStageType(node.type()));
        if (stageGraph) {
            runWhiteBox(graph, scoped, listener);
            return;
        }

        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
            FlowNode node = graph.node(nodeId);
            switch (node.type()) {
                case "start" -> lastOutcome = "default";
                case "end" -> {
                    return;
                }
                case "builtin_ask" -> {
                    askService.ask(scoped, listener);
                    lastOutcome = "default";
                }
                case "llm_chat" -> {
                    AskRunRecorder recorder = askService.beginRun(scoped, listener);
                    try {
                        askService.runLlmChat(
                                scoped,
                                recorder,
                                stringConfig(node, "systemPrompt", "你是有帮助的中文助手。"),
                                AskSkillIds.normalize(node.config().get("skills"), AskSkillIds.DEFAULT_LLM_CHAT),
                                knowledgeTools);
                        lastOutcome = "default";
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

    /** 阶段图：单次 run 信封，按节点真实执行并用 outcome 选边。 */
    private void runWhiteBox(FlowGraph graph, AskRequest scoped, AskListener listener) {
        AskRunRecorder recorder = askService.beginRun(scoped, listener);
        AskFlowState state = new AskFlowState();
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        try {
            for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
                FlowNode node = graph.node(nodeId);
                switch (node.type()) {
                    case "start" -> lastOutcome = "default";
                    case "end" -> {
                        return;
                    }
                    case "knowledge" -> {
                        int topK = intConfig(node, "topK", AskKnowledgeService.DEFAULT_TOP_K);
                        lastOutcome = askService.runKnowledge(scoped, recorder, state, topK);
                    }
                    case "ontology" -> lastOutcome = askService.runOntology(
                            scoped,
                            recorder,
                            state,
                            AskSkillIds.normalize(node.config().get("skills"), AskSkillIds.DEFAULT_ONTOLOGY));
                    case "metadata" -> lastOutcome = askService.runMetadata(
                            scoped, recorder, state, boolConfig(node, "recover", true));
                    case "insight" -> lastOutcome = askService.runInsight(
                            scoped,
                            recorder,
                            state,
                            AskSkillIds.normalize(node.config().get("skills"), AskSkillIds.DEFAULT_INSIGHT));
                    case "llm_chat" -> {
                        askService.runLlmChat(
                                scoped,
                                recorder,
                                stringConfig(node, "systemPrompt", "你是有帮助的中文助手。"),
                                AskSkillIds.normalize(node.config().get("skills"), AskSkillIds.DEFAULT_LLM_CHAT),
                                knowledgeTools);
                        lastOutcome = "default";
                    }
                    case "builtin_ask" -> throw new IllegalArgumentException("白盒阶段图不应包含 builtin_ask");
                    default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
                }
                if ("end".equals(node.type())) {
                    return;
                }
                String next = graph.next(nodeId, lastOutcome);
                if (next == null) {
                    if (!"answered".equals(lastOutcome)
                            && !"clarify".equals(lastOutcome)
                            && !"error".equals(lastOutcome)) {
                        listener.emit("error", Map.of("message", "流程在节点 " + nodeId + " 后没有出口（outcome=" + lastOutcome + ")"));
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

    /** 禁止在同一张图里混用整段 builtin_ask 与拆分阶段节点。 */
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

    /** 截断对话历史；优先读开始节点 config.memoryWindowTurns。 */
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

    private static int intConfig(FlowNode node, String key, int fallback) {
        Object value = node.config().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolConfig(FlowNode node, String key, boolean fallback) {
        Object value = node.config().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.strip());
        }
        return fallback;
    }
}
