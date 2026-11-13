package com.cmcc.cmdevops.security.federation.ontology.draft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.cmcc.cmdevops.security.federation.llm.JsonReply;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class DraftJson {

    private DraftJson() {
    }

    public static Snapshot parseDraft(ObjectMapper objectMapper, String reply) {
        String json = JsonReply.extractObject(reply);
        JacksonException failure;
        try {
            return bindDraft(objectMapper, json);
        } catch (JacksonException e) {
            failure = e;
        }
        for (String candidate : List.of(repairJson(json), closeTruncated(repairJson(json)))) {
            if (candidate.equals(json)) {
                continue;
            }
            try {
                return bindDraft(objectMapper, candidate);
            } catch (JacksonException ignored) {
                // 下一种修补再试
            }
        }
        throw new IllegalStateException("大模型返回的 JSON 无法解析：" + failure.getOriginalMessage(), failure);
    }

    private static Snapshot bindDraft(ObjectMapper objectMapper, String json) throws JacksonException {
        JsonNode root = objectMapper.readTree(json);
        coerceDraft(root);
        return objectMapper.readerFor(Snapshot.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .readValue(root);
    }

    /** 模型会照抄缺冒号的字段清单，或在写完一个对象后被截断。只修补这两种，不改已经合法的 JSON。 */
    public static String repairJson(String json) {
        return MISSING_COLON.matcher(json).replaceAll("$1:");
    }

    public static String closeTruncated(String json) {
        StringBuilder kept = new StringBuilder();
        ArrayDeque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                kept.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                kept.append(c);
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(c);
                kept.append(c);
            } else if ((c == '}' || c == ']') && !stack.isEmpty() && matches(stack.peek(), c)) {
                stack.pop();
                kept.append(c);
            } else if (c != '}' && c != ']') {
                kept.append(c);
            }
        }
        if (inString) {
            int quote = kept.lastIndexOf("\"");
            if (quote >= 0) {
                kept.setLength(quote);
            }
        }
        trimDangling(kept);
        while (!stack.isEmpty()) {
            kept.append(stack.pop() == '{' ? '}' : ']');
        }
        return kept.toString();
    }

    private static boolean matches(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']');
    }

    private static void trimDangling(StringBuilder kept) {
        int end = kept.length();
        while (end > 0 && Character.isWhitespace(kept.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            kept.setLength(0);
            return;
        }
        char last = kept.charAt(end - 1);
        if (last != ',' && last != ':') {
            kept.setLength(end);
            return;
        }
        end--;
        while (end > 0 && Character.isWhitespace(kept.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && kept.charAt(end - 1) == '"') {
            int open = kept.lastIndexOf("\"", end - 2);
            if (open >= 0) {
                end = open;
            }
            while (end > 0 && Character.isWhitespace(kept.charAt(end - 1))) {
                end--;
            }
            if (end > 0 && kept.charAt(end - 1) == ',') {
                end--;
            }
        }
        kept.setLength(Math.max(end, 0));
    }

    private static final Pattern MISSING_COLON = Pattern.compile(
            "([\\{,]\\s*\"(?:\\\\.|[^\"\\\\])*\")\\s*,(?=\\s*(?:\"(?!(?:"
                    + "name|labelZh|labelEn|aliases|comment|parent|table|timeColumn|largeTable|domain|datatype|column|unit|identifier|codeList|value|range|joinFrom|joinTo|inverse|transitive|functional|base|definitionText|attribute|operator|conditions|concepts|attributes|relations|codes|definedConcepts"
                    + ")\")|\\d|true|false|null|[\\{\\[-]))");

    /** 模型常把 column、中文名等字符串字段写成单元素数组，先收成字符串，避免整段属性被丢弃。 */
    public static void coerceDraft(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>(object.propertyNames());
            for (String field : fields) {
                JsonNode value = object.get(field);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (STRING_FIELDS.contains(field) && !value.isTextual()) {
                    object.put(field, value.isArray() ? firstScalar(value) : value.asString());
                } else if ("aliases".equals(field) && value.isString()) {
                    object.putArray(field).add(value.asString());
                } else if (BOOL_FIELDS.contains(field) && !value.isBoolean()) {
                    object.put(field, asBoolean(value));
                } else {
                    coerceDraft(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                coerceDraft(child);
            }
        }
    }

    private static final Set<String> STRING_FIELDS = Set.of(
            "name", "labelZh", "labelEn", "comment", "parent", "table", "timeColumn", "domain", "datatype",
            "column", "unit", "codeList", "value", "range", "joinFrom", "joinTo", "inverse", "base",
            "definitionText", "attribute", "operator");
    private static final Set<String> BOOL_FIELDS = Set.of("largeTable", "identifier", "transitive", "functional");

    private static String firstScalar(JsonNode array) {
        for (JsonNode item : array) {
            if (item == null || item.isNull() || item.isObject()) {
                continue;
            }
            String text = item.isArray() ? firstScalar(item) : item.asString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static boolean asBoolean(JsonNode value) {
        JsonNode node = value.isArray() ? firstNode(value) : value;
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        String text = node.asString();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static JsonNode firstNode(JsonNode array) {
        for (JsonNode item : array) {
            if (item != null && !item.isNull()) {
                return item.isArray() ? firstNode(item) : item;
            }
        }
        return null;
    }
}
