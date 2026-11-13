package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.AttributeView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.CodeView;

/**
 * 用本体码值校验过滤字面量（对齐 Auto Ontology「filter value validation」）：
 * 中文标签误写入 WHERE 时可建议改为正式 value。
 */
public final class FilterValueValidator {

    private static final Pattern STRING_LITERAL = Pattern.compile("'((?:\\\\'|[^'])*)'|\"((?:\\\\\"|[^\"]*)*)\"");

    private FilterValueValidator() {
    }

    public static Map<String, Object> validate(BusinessContext context, String attributeOrColumn, String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attributeOrColumn", attributeOrColumn);
        result.put("value", value);
        AttributeView attribute = findAttribute(context, attributeOrColumn);
        if (attribute == null) {
            result.put("ok", true);
            result.put("message", "上下文中无该属性/列的码表，跳过码值校验");
            return result;
        }
        result.put("attribute", attribute.name());
        result.put("column", attribute.column());
        List<CodeView> codes = attribute.codes() == null ? List.of() : attribute.codes();
        if (codes.isEmpty()) {
            result.put("ok", true);
            result.put("message", "该属性没有码值清单");
            return result;
        }
        String needle = value == null ? "" : value.strip();
        for (CodeView code : codes) {
            if (equalsIgnoreCase(code.value(), needle)) {
                result.put("ok", true);
                result.put("matchedBy", "value");
                result.put("code", code.name());
                result.put("message", "过滤值与码值 value 一致，请使用 '" + code.value() + "'");
                return result;
            }
        }
        for (CodeView code : codes) {
            if (equalsIgnoreCase(code.labelZh(), needle) || aliasesContain(code.aliases(), needle)) {
                result.put("ok", false);
                result.put("matchedBy", "label");
                result.put("code", code.name());
                result.put("suggestedValue", code.value());
                result.put("message", "应用码值 value='" + code.value() + "'，不要用中文名 '" + needle + "'");
                return result;
            }
        }
        result.put("ok", false);
        result.put("message", "值不在码表中");
        result.put("allowedValues", codes.stream().map(CodeView::value).distinct().limit(20).toList());
        result.put("allowedLabels", codes.stream().map(CodeView::labelZh).filter(s -> s != null && !s.isBlank())
                .distinct().limit(20).toList());
        return result;
    }

    /** 从 SQL 文本抽出字符串字面量，对照上下文全部码表给出提示。 */
    public static List<Map<String, Object>> scanSqlLiterals(BusinessContext context, String sql) {
        if (sql == null || sql.isBlank() || context == null) {
            return List.of();
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(sql);
        while (matcher.find()) {
            String literal = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (literal == null || literal.isBlank()) {
                continue;
            }
            literal = literal.replace("\\'", "'").replace("\\\"", "\"");
            for (AttributeView attribute : context.attributes()) {
                if (attribute.codes() == null || attribute.codes().isEmpty()) {
                    continue;
                }
                Map<String, Object> check = validate(context, attribute.name(), literal);
                if (Boolean.FALSE.equals(check.get("ok")) || "label".equals(check.get("matchedBy"))) {
                    Map<String, Object> finding = new LinkedHashMap<>(check);
                    finding.put("literal", literal);
                    findings.add(finding);
                }
            }
        }
        return findings;
    }

    private static AttributeView findAttribute(BusinessContext context, String attributeOrColumn) {
        if (attributeOrColumn == null || attributeOrColumn.isBlank() || context == null) {
            return null;
        }
        String needle = attributeOrColumn.strip();
        for (AttributeView attribute : context.attributes()) {
            if (needle.equalsIgnoreCase(attribute.name()) || needle.equalsIgnoreCase(attribute.column())) {
                return attribute;
            }
            if (attribute.labelZh() != null && needle.equalsIgnoreCase(attribute.labelZh())) {
                return attribute;
            }
        }
        // also search nested under concepts
        for (var concept : context.concepts()) {
            if (concept.attributes() == null) {
                continue;
            }
            for (AttributeView attribute : concept.attributes()) {
                if (needle.equalsIgnoreCase(attribute.name()) || needle.equalsIgnoreCase(attribute.column())) {
                    return attribute;
                }
            }
        }
        return null;
    }

    private static boolean aliasesContain(List<String> aliases, String needle) {
        if (aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            if (equalsIgnoreCase(alias, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.strip().equalsIgnoreCase(b.strip());
    }
}
