package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;

/**
 * 一次流程运行的变量池：{@code sys.*} + {@code <nodeId>.*}。
 */
public final class FlowVariablePool {

    private final Map<String, Object> sys = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();

    public static FlowVariablePool fromRequest(AskRequest request, String user) {
        FlowVariablePool pool = new FlowVariablePool();
        pool.sys.put("query", request.question() == null ? "" : request.question());
        pool.sys.put("history", request.history() == null ? List.<Turn>of() : request.history());
        pool.sys.put("conversation_id", request.conversationId() == null ? "" : request.conversationId());
        pool.sys.put("app_id", request.appId() == null ? "" : request.appId());
        pool.sys.put("user", user == null ? "" : user);
        return pool;
    }

    public Map<String, Object> sys() {
        return sys;
    }

    public void putNode(String nodeId, Map<String, Object> outputs) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (outputs != null) {
            copy.putAll(outputs);
        }
        nodes.put(nodeId, copy);
    }

    public Map<String, Object> node(String nodeId) {
        return nodes.getOrDefault(nodeId, Map.of());
    }

    public Map<String, Map<String, Object>> nodes() {
        return nodes;
    }

    /** 点分路径：{@code sys.query} 或 {@code knowledge.answer}。 */
    public Object get(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.strip();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return null;
        }
        String ns = trimmed.substring(0, dot);
        String field = trimmed.substring(dot + 1);
        if ("sys".equals(ns)) {
            return dig(sys.get(field.split("\\.")[0]), field.contains(".") ? field.substring(field.indexOf('.') + 1) : null);
        }
        Map<String, Object> node = nodes.get(ns);
        if (node == null) {
            return null;
        }
        if (!field.contains(".")) {
            return node.get(field);
        }
        String head = field.substring(0, field.indexOf('.'));
        String rest = field.substring(field.indexOf('.') + 1);
        return dig(node.get(head), rest);
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Object value, String rest) {
        if (rest == null || rest.isBlank() || value == null) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            String head;
            String next;
            int dot = rest.indexOf('.');
            if (dot < 0) {
                head = rest;
                next = null;
            } else {
                head = rest.substring(0, dot);
                next = rest.substring(dot + 1);
            }
            Object child = ((Map<String, Object>) map).get(head);
            return dig(child, next);
        }
        return null;
    }

    /** 可供 UI / 调试列出的变量路径。 */
    public List<String> listPaths() {
        List<String> paths = new java.util.ArrayList<>();
        for (String key : sys.keySet()) {
            paths.add("sys." + key);
        }
        for (Map.Entry<String, Map<String, Object>> entry : nodes.entrySet()) {
            for (String key : entry.getValue().keySet()) {
                paths.add(entry.getKey() + "." + key);
            }
        }
        return paths;
    }
}
