package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dify 风格变量引用：{@code {{#sys.query#}}}、{@code {{#knowledge.answer#}}}。
 */
public final class FlowVarRefs {

    private static final Pattern REF = Pattern.compile("\\{\\{#([a-zA-Z0-9_./-]+)#\\}\\}");

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
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(stringify(item));
            }
            return String.join("\n", parts);
        }
        return String.valueOf(value);
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
}
