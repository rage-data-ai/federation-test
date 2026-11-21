package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流程的有向图：节点 + 边。边条件为空或 {@code default} 表示兜底出口。
 */
public record FlowGraph(String startNodeId, List<FlowNode> nodes, List<FlowEdge> edges) {

    public FlowGraph {
        Objects.requireNonNull(startNodeId, "startNodeId");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public FlowNode node(String id) {
        return nodes.stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("流程节点不存在：" + id));
    }

    public Map<String, FlowNode> nodeMap() {
        Map<String, FlowNode> map = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            map.put(node.id(), node);
        }
        return map;
    }

    /**
     * 按条件选下一条边。
     * <ul>
     *   <li>{@code continue} 与 {@code default} 同义</li>
     *   <li>{@code clarify} / {@code answered} / {@code default}：无精确边时可回落 default</li>
     *   <li>其它 outcome（如 {@code true}/{@code false}/{@code error}）：无精确边即叶子终止（不回落），
     *       对齐 ChatFlow 无 end 时「未连出的分支即停」</li>
     * </ul>
     */
    public String next(String fromId, String outcome) {
        String want = normalizeWhen(outcome);
        String fallback = null;
        for (FlowEdge edge : edges) {
            if (!edge.from().equals(fromId)) {
                continue;
            }
            String when = normalizeWhen(edge.when());
            if (when.equalsIgnoreCase(want)) {
                return edge.to();
            }
            if ("default".equalsIgnoreCase(when)) {
                fallback = edge.to();
            }
        }
        if ("default".equalsIgnoreCase(want)
                || "clarify".equalsIgnoreCase(want)
                || "answered".equalsIgnoreCase(want)) {
            return fallback;
        }
        return null;
    }

    static String normalizeWhen(String when) {
        if (when == null || when.isBlank()) {
            return "default";
        }
        String w = when.strip();
        if ("continue".equalsIgnoreCase(w)) {
            return "default";
        }
        return w;
    }

    public void validate() {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("流程至少需要一个节点");
        }
        Map<String, FlowNode> map = nodeMap();
        if (!map.containsKey(startNodeId)) {
            throw new IllegalArgumentException("startNodeId 不在节点列表中：" + startNodeId);
        }
        for (FlowEdge edge : edges) {
            if (!map.containsKey(edge.from()) || !map.containsKey(edge.to())) {
                throw new IllegalArgumentException("边引用了不存在的节点：" + edge.from() + " → " + edge.to());
            }
        }
        // ChatFlow 对齐 Dify：不要求 end；最终用户可见回复必须经回答节点。
        boolean hasAnswer = nodes.stream().anyMatch(node -> "answer".equals(node.type()));
        if (!hasAnswer) {
            throw new IllegalArgumentException("流程需要至少一个回答节点作为对话出口");
        }
        java.util.HashSet<String> titles = new java.util.HashSet<>();
        for (FlowNode node : nodes) {
            String title = node.title() == null ? "" : node.title().strip();
            if (title.isBlank()) {
                throw new IllegalArgumentException("节点名称不能为空：" + node.id());
            }
            if (title.indexOf('#') >= 0
                    || title.indexOf('.') >= 0
                    || title.indexOf('{') >= 0
                    || title.indexOf('}') >= 0) {
                throw new IllegalArgumentException("节点名称不能包含 # . { }：" + title);
            }
            if (!titles.add(title)) {
                throw new IllegalArgumentException("节点名称不能重复：" + title);
            }
        }
    }

    public record FlowNode(String id, String type, String title, Map<String, Object> config) {
        public FlowNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            title = title == null || title.isBlank() ? type : title;
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    public record FlowEdge(String from, String to, String when) {
        public FlowEdge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    public static FlowGraph of(String startNodeId, List<FlowNode> nodes, List<FlowEdge> edges) {
        FlowGraph graph = FlowGraphMigrations.normalize(
                new FlowGraph(startNodeId, new ArrayList<>(nodes), new ArrayList<>(edges)));
        graph.validate();
        return graph;
    }
}
