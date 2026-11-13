package com.cmcc.cmdevops.security.federation.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本体实体命名：概念、属性、码值集合由映射表/列确定性生成。
 */
final class OntologyNaming {

    private OntologyNaming() {
    }

    /**
     * 码值集合名只由属性映射的表和列决定，同一张表的同一列每次起草都得到同一个名字。
     * 例如 ck81.pangu.wls_day01 的 Status 列为 Ck81PanguWlsDay01_Status。
     */
    static String codeListName(String table, String column) {
        String tablePart = pascal(table.trim().toLowerCase(Locale.ROOT));
        String columnPart = pascal(column.trim());
        if (columnPart.isEmpty()) {
            columnPart = "Col" + Integer.toUnsignedString(column.trim().hashCode(), 36);
        }
        String name = tablePart + "_" + columnPart;
        return Character.isLetter(name.charAt(0)) ? name : "T" + name;
    }

    /**
     * 概念名由映射表决定，例如 ck81.pangu.wls_day01 为 WlsDay01。
     * 表名与别的表的概念重名时依次改用 schema+表名、catalog+schema+表名。
     *
     * @param tableOf 已占用的概念名及其映射表
     */
    static String conceptName(String table, Map<String, String> tableOf) {
        String[] parts = table.trim().toLowerCase(Locale.ROOT).split("\\.");
        List<String> candidates = new ArrayList<>();
        for (int from = parts.length - 1; from >= 0; from--) {
            String name = pascal(String.join("_", List.of(parts).subList(from, parts.length)));
            candidates.add(name.isEmpty() || !Character.isLetter(name.charAt(0)) ? "T" + name : name);
        }
        for (String candidate : candidates) {
            String owner = tableOf.get(candidate);
            if (owner == null || owner.equalsIgnoreCase(table.trim())) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 属性名由概念和列决定，例如概念 WlsDay01 的 LogonType 列为 wlsDay01_LogonType。
     * 首字母小写，不会与概念、码值集合、码值重名；列名不是合法标识符时改用 PascalCase。
     */
    static String attributeName(String concept, String column) {
        String trimmed = column.trim();
        String columnPart = trimmed.matches("[A-Za-z0-9_]+") ? trimmed : pascal(trimmed);
        if (columnPart.isEmpty()) {
            columnPart = "Col" + Integer.toUnsignedString(trimmed.hashCode(), 36);
        }
        return Character.toLowerCase(concept.charAt(0)) + concept.substring(1) + "_" + columnPart;
    }

    static String pascal(String text) {
        StringBuilder out = new StringBuilder();
        for (String part : text.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }
}
