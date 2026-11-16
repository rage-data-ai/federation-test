package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRunRecorder;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/**
 * 按发布图执行节点。
 * <ul>
 *   <li>{@code builtin_ask}：整段交给 {@link AskService}</li>
 *   <li>{@code knowledge}/{@code ontology}/{@code metadata}/{@code insight}：折叠为一次 {@link AskService#ask}
 *       （画布展开展示；阶段边条件待真正拆跑时再生效）</li>
 *   <li>{@code llm_chat}：一次 LLM 补全</li>
 * </ul>
 */
@Component
public class FlowRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowRunner.class);
    private static final int MAX_HOPS = 32;

    private final AskService askService;
    private final LlmClient llm;
    private final AskFeedbackStore feedbackStore;
    private final FederationProperties properties;

    public FlowRunner(
            AskService askService, LlmClient llm, AskFeedbackStore feedbackStore, FederationProperties properties) {
        this.askService = askService;
        this.llm = llm;
        this.feedbackStore = feedbackStore;
        this.properties = properties;
    }

    public void run(PublishedApp app, AskRequest request, AskListener listener) {
        FlowGraph graph = app.graph();
        graph.validate();
        assertCompatibleAskNodes(graph);
        AskRequest scoped = withHistoryWindow(graph, withAppMeta(request, app));
        String nodeId = graph.startNodeId();
        String lastOutcome = "default";
        boolean pipelineHandled = false;
        for (int hop = 0; hop < MAX_HOPS && nodeId != null; hop++) {
            FlowNode node = graph.node(nodeId);
            switch (node.type()) {
                case "start" -> lastOutcome = "default";
                case "end" -> {
                    return;
                }
                case "builtin_ask" -> {
                    askService.ask(scoped, listener);
                    pipelineHandled = true;
                    lastOutcome = "default";
                }
                case "knowledge", "ontology", "metadata", "insight" -> {
                    if (!pipelineHandled) {
                        askService.ask(scoped, listener);
                        pipelineHandled = true;
                    }
                    // 折叠执行后沿 continue/default 走完剩余阶段节点，避免重复调用 AskService。
                    lastOutcome = "continue";
                }
                case "llm_chat" -> {
                    runLlmChat(node, scoped, listener);
                    pipelineHandled = true;
                    lastOutcome = "default";
                }
                default -> throw new IllegalArgumentException("暂不支持的节点类型：" + node.type());
            }
            if ("end".equals(node.type())) {
                return;
            }
            String next = graph.next(nodeId, lastOutcome);
            if (next == null) {
                if (!pipelineHandled) {
                    listener.emit("error", Map.of("message", "流程在节点 " + nodeId + " 后没有出口"));
                }
                return;
            }
            nodeId = next;
        }
        log.warn("流程跳转超过 {} 次，已停止 appId={}", MAX_HOPS, app.appId());
    }

    /** 禁止在同一张图里混用整段 builtin_ask 与拆分阶段节点。 */
    static void assertCompatibleAskNodes(FlowGraph graph) {
        boolean hasBuiltin = graph.nodes().stream().anyMatch(node -> "builtin_ask".equals(node.type()));
        boolean hasStages = graph.nodes().stream().anyMatch(node -> DefaultFlows.isAskStageType(node.type()));
        if (hasBuiltin && hasStages) {
            throw new IllegalArgumentException("流程不能同时包含 builtin_ask 与 knowledge/ontology/metadata/insight 阶段节点");
        }
    }

    private void runLlmChat(FlowNode node, AskRequest request, AskListener listener) {
        llm.model();
        AskRunRecorder recorder = new AskRunRecorder(
                request, GatewayUser.current().name(), listener, System.currentTimeMillis());
        listener.emit("run", Map.of(
                "runId", recorder.id(),
                "conversationId", recorder.conversationId(),
                "appId", request.appId() == null ? "" : request.appId()));
        try {
            listener.emit("stage", Map.of("name", "llm", "message", "大模型对话"));
            String system = stringConfig(node, "systemPrompt", "你是有帮助的中文助手。");
            String user = dialoguePrompt(request);
            String answer = llm.complete(system, user);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer", answer == null ? "" : answer.strip());
            payload.put("sql", null);
            payload.put("caliber", List.of());
            payload.put("steps", List.of(Map.of("agent", "llm_chat", "detail", node.title())));
            listener.emit("answer", payload);
        } catch (RuntimeException e) {
            recorder.failed(e);
            throw e;
        } finally {
            try {
                feedbackStore.saveRun(recorder.toRun(System.currentTimeMillis()));
            } catch (RuntimeException e) {
                log.warn("llm_chat 记录保存失败: {}", e.toString());
            }
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

    private static String dialoguePrompt(AskRequest request) {
        StringBuilder sb = new StringBuilder();
        List<Turn> history = request.history() == null ? List.of() : request.history();
        if (!history.isEmpty()) {
            sb.append("对话历史：\n");
            sb.append(history.stream()
                    .map(turn -> turn.role() + ": " + turn.content())
                    .collect(Collectors.joining("\n")));
            sb.append("\n\n");
        }
        sb.append("用户：").append(request.question().strip());
        return sb.toString();
    }
}
