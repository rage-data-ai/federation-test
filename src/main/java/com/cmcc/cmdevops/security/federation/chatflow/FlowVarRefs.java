package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dify 风格变量引用：{@code {{#sys.query#}}}、{@code {{#knowledge.answer#}}}。
 */
public final class FlowVarRefs {

    /** 允许中文节点名：{@code {{#知识库.result#}}}。 */
    private static final Pattern REF = Pattern.compile("\\{\\{#([^#]+?)#\\}\\}");

    private FlowVarRefs() {
    }

    public static boolean isRef(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String stripped = text.strip();
        return stripped.startsWith("{{#") && stripped.endsWith("#}}") && stripped.indexOf("{{#") == stripped.lastIndexOf("{{#");
    }

    public static String refPath(Object value) {
        if (!isRef(value)) {
            return null;
        }
        String text = value.toString().strip();
        return text.substring(3, text.length() - 3).strip();
    }

    public static Object resolve(Object raw, FlowVariablePool pool) {
        if (raw == null) {
            return null;
        }
        if (isRef(raw)) {
            return pool.get(refPath(raw));
        }
        if (raw instanceof String text && text.contains("{{#")) {
            return interpolate(text, pool);
        }
        return raw;
    }

    public static String interpolate(String template, FlowVariablePool pool) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher matcher = REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = pool.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, Object> resolveInputs(
            Map<String, Object> declaredDefaults,
            Map<String, Object> configured,
            FlowVariablePool pool) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (declaredDefaults != null) {
            merged.putAll(declaredDefaults);
        }
        if (configured != null) {
            merged.putAll(configured);
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            resolved.put(entry.getKey(), resolve(entry.getValue(), pool));
        }
        return resolved;
    }

    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return toJson(value);
        }
        return String.valueOf(value);
    }

    /** 提示词插值用：Map/List 写成紧凑 JSON，便于 {{#knowledge.result#}} 引用。 */
    @SuppressWarnings("unchecked")
    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quoteJson(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quoteJson(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJson(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return quoteJson(String.valueOf(value));
    }

    private static String quoteJson(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String asString(Map<String, Object> inputs, String key, String fallback) {
        Object value = inputs.get(key);
        if (value == null) {
            return fallback;
        }
        String text = stringify(value).strip();
        return text.isEmpty() ? fallback : text;
    }

    public static int asInt(Map<String, Object> inputs, String key, int fallback) {
        Object value = inputs.get(key);
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

    public static boolean asBool(Map<String, Object> inputs, String key, boolean fallback) {
        Object value = inputs.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.strip());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Object asSkills(Map<String, Object> inputs, String key, List<String> fallback) {
        Object value = inputs.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Iterable<?> || value instanceof String) {
            return value;
        }
        return fallback;
    }

    /** 二路条件比较：{@code > >= < <= == != empty not_empty}。 */
    public static boolean compare(Object left, String op, Object right) {
        String operator = op == null || op.isBlank() ? ">" : op.strip().toLowerCase();
        if ("empty".equals(operator)) {
            return isEmpty(left);
        }
        if ("not_empty".equals(operator)) {
            return !isEmpty(left);
        }
        if ("==".equals(operator) || "eq".equals(operator)) {
            return Objects.equals(normalize(left), normalize(right));
        }
        if ("!=".equals(operator) || "neq".equals(operator)) {
            return !Objects.equals(normalize(left), normalize(right));
        }
        Double l = asDouble(left);
        Double r = asDouble(right);
        if (l == null || r == null) {
            String ls = stringify(left);
            String rs = stringify(right);
            int cmp = ls.compareTo(rs);
            return switch (operator) {
                case ">", "gt" -> cmp > 0;
                case ">=", "gte" -> cmp >= 0;
                case "<", "lt" -> cmp < 0;
                case "<=", "lte" -> cmp <= 0;
                default -> false;
            };
        }
        return switch (operator) {
            case ">", "gt" -> l > r;
            case ">=", "gte" -> l >= r;
            case "<", "lt" -> l < r;
            case "<=", "lte" -> l <= r;
            default -> false;
        };
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            Double asNum = asDouble(stripped);
            return asNum != null ? asNum : stripped;
        }
        return value;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
