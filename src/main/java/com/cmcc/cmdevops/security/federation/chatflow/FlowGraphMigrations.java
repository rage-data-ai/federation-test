package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowEdge;
import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph.FlowNode;

/**
 * ChatFlow 图迁移：对齐 Dify Chatflow（以 Answer 收束，无 End/Output）。
 *
 * <p>旧图若仍含 {@code end}：删除结束节点；若图中尚无 {@code answer}，把原出口 end 转成
 * answer（最终用户可见回复必须经回答节点）。
 */
public final class FlowGraphMigrations {

    private FlowGraphMigrations() {
    }

    /** 规范化：去掉 end，并确保可落到回答节点。 */
    public static FlowGraph normalize(FlowGraph graph) {
        if (graph == null) {
            return null;
        }
        return stripEndNodes(graph);
    }

    static FlowGraph stripEndNodes(FlowGraph graph) {
        List<FlowNode> ends = graph.nodes().stream().filter(node -> "end".equals(node.type())).toList();
        if (ends.isEmpty()) {
            return normalizeAnswerText(graph);
        }

        Set<String> endIds = ends.stream().map(FlowNode::id).collect(Collectors.toSet());
        boolean hasAnswer = graph.nodes().stream()
                .anyMatch(node -> !"end".equals(node.type()) && "answer".equals(node.type()));

        List<FlowNode> keptNodes = new ArrayList<>();
        for (FlowNode node : graph.nodes()) {
            if ("end".equals(node.type())) {
                continue;
            }
            keptNodes.add(normalizeAnswerNode(node));
        }

        List<FlowEdge> keptEdges = graph.edges().stream()
                .filter(edge -> !endIds.contains(edge.from()) && !endIds.contains(edge.to()))
                .toList();

        // 无回答节点时：把第一个 end 转成 answer，并接回原入边（llm→end 变为 llm→answer）
        if (!hasAnswer) {
            FlowNode firstEnd = ends.get(0);
            String answerId = firstEnd.id();
            String answerTitle = "结束".equals(firstEnd.title()) || "end".equalsIgnoreCase(firstEnd.title())
                    ? "回答"
                    : firstEnd.title();
            Map<String, Object> config = new LinkedHashMap<>(firstEnd.config());
            Object upstreamHint = null;
            for (FlowEdge edge : graph.edges()) {
                if (endIds.contains(edge.to()) && !endIds.contains(edge.from())) {
                    try {
                        FlowNode from = graph.node(edge.from());
                        if ("llm_chat".equals(from.type()) || "insight".equals(from.type())) {
                            upstreamHint = "{{#" + from.title() + ".answer#}}";
                            break;
                        }
                    } catch (RuntimeException ignored) {
                        // ignore
                    }
                }
            }
            String text = upstreamHint != null ? String.valueOf(upstreamHint) : "{{#sys.query#}}";
            config.put("text", text);
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = config.get("inputs") instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : new LinkedHashMap<>();
            inputs.putIfAbsent("text", text);
            config.put("inputs", inputs);
            keptNodes.add(new FlowNode(answerId, "answer", answerTitle, config));

            List<FlowEdge> rebound = new ArrayList<>(keptEdges);
            for (FlowEdge edge : graph.edges()) {
                if (endIds.contains(edge.to()) && !endIds.contains(edge.from())) {
                    rebound.add(new FlowEdge(edge.from(), answerId, edge.when()));
                }
            }
            keptEdges = rebound;
        }

        String start = graph.startNodeId();
        if (endIds.contains(start) && !keptNodes.isEmpty()) {
            start = keptNodes.get(0).id();
        }
        return new FlowGraph(start, keptNodes, keptEdges);
    }

    private static FlowGraph normalizeAnswerText(FlowGraph graph) {
        List<FlowNode> nodes = graph.nodes().stream().map(FlowGraphMigrations::normalizeAnswerNode).toList();
        return new FlowGraph(graph.startNodeId(), nodes, graph.edges());
    }

    /** 把顶层 {@code config.text} 同步进 {@code config.inputs.text}，两边都能读。 */
    @SuppressWarnings("unchecked")
    static FlowNode normalizeAnswerNode(FlowNode node) {
        if (node == null || !"answer".equals(node.type())) {
            return node;
        }
        Map<String, Object> config = new LinkedHashMap<>(node.config());
        Object topText = config.get("text");
        Map<String, Object> inputs = config.get("inputs") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        if (!inputs.containsKey("text") && topText != null) {
            inputs.put("text", topText);
        }
        if (!config.containsKey("text") && inputs.get("text") != null) {
            config.put("text", inputs.get("text"));
        }
        if (!inputs.containsKey("text") && !config.containsKey("text")) {
            inputs.put("text", "{{#sys.query#}}");
            config.put("text", "{{#sys.query#}}");
        }
        config.put("inputs", inputs);
        return new FlowNode(node.id(), node.type(), node.title(), config);
    }
}
