package com.cmcc.cmdevops.security.federation.ask;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ChatFlow / 大模型节点可勾选的工具技能 id（与前端调色板对齐）。
 */
public final class AskSkillIds {

    public static final String ONTOLOGY = "ontology";
    public static final String PREFERENCE = "preference";
    public static final String KNOWLEDGE = "knowledge";
    public static final String SQL = "sql";

    public static final Set<String> ALL = Set.of(ONTOLOGY, PREFERENCE, KNOWLEDGE, SQL);

    public static final List<String> DEFAULT_ONTOLOGY = List.of(ONTOLOGY);
    public static final List<String> DEFAULT_INSIGHT = List.of(SQL, PREFERENCE);
    public static final List<String> DEFAULT_LLM_CHAT = List.of();

    private AskSkillIds() {
    }

    public static Set<String> normalize(Object raw, List<String> defaults) {
        if (raw == null) {
            return new LinkedHashSet<>(defaults == null ? List.of() : defaults);
        }
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    String id = item.toString().strip();
                    if (ALL.contains(id)) {
                        out.add(id);
                    }
                }
            }
            return out;
        }
        if (raw instanceof String text && !text.isBlank()) {
            for (String part : text.split("[,\\s]+")) {
                String id = part.strip();
                if (ALL.contains(id)) {
                    out.add(id);
                }
            }
            return out;
        }
        return new LinkedHashSet<>(defaults == null ? List.of() : defaults);
    }
}
