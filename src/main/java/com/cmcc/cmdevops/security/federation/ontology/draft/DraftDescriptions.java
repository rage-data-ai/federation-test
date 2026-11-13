package com.cmcc.cmdevops.security.federation.ontology.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

public final class DraftDescriptions {

    private DraftDescriptions() {
    }

    private static final int MAX_CELL = 40;

    public static String describeColumnNames(List<TableSnapshot> tables) {
        StringBuilder text = new StringBuilder();
        for (TableSnapshot table : tables) {
            List<ColumnSnapshot> columns = table.getColumns() == null ? List.of() : table.getColumns();
            text.append("\n表 ").append(table.key()).append(" 的列：");
            text.append(columns.stream().map(ColumnSnapshot::getName).collect(Collectors.joining("、")));
            text.append('\n');
        }
        return text.toString();
    }
    public static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            OntologyDraftService.ValueLookup lookup,
            Map<String, Set<String>> allowedCodes) {
        return describeCodeEvidence(snapshot, tables, samples, lookup, allowedCodes, Set.of());
    }

    public static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            OntologyDraftService.ValueLookup lookup,
            Map<String, Set<String>> allowedCodes,
            Set<String> coveredCodeLists) {
        StringBuilder text = new StringBuilder();
        boolean any = false;
        for (Attribute attribute : list(snapshot.attributes())) {
            if (attribute.codeList() == null || attribute.codeList().isBlank() || attribute.column() == null
                    || coveredCodeLists.contains(attribute.codeList())) {
                continue;
            }
            any = true;
            String table = conceptTable(snapshot, attribute.domain());
            Set<String> values = new java.util.LinkedHashSet<>();
            if (lookup != null && table != null) {
                values.addAll(lookup.values(table, attribute.column()));
            }
            values.addAll(sampleValues(tables, samples, table, attribute.column()));
            values.removeIf(value -> value.length() > 40);
            text.append("属性 ").append(attribute.name()).append("，码值集合 ").append(attribute.codeList())
                    .append("，表 ").append(Objects.toString(table, "无")).append("，列 ").append(attribute.column()).append('\n');
            if (values.isEmpty()) {
                text.append("取值：无\n");
            } else {
                List<String> capped = values.stream().limit(20).toList();
                allowedCodes.computeIfAbsent(attribute.codeList(), key -> new java.util.LinkedHashSet<>()).addAll(capped);
                text.append("取值：").append(String.join("、", capped)).append('\n');
            }
        }
        return any ? text.toString() : null;
    }

    public static String describeRelations(Snapshot snapshot) {
        if (list(snapshot.relations()).isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n已有关系：\n");
        for (Relation relation : snapshot.relations()) {
            text.append("- ").append(relation.name()).append("：").append(relation.domain()).append('.')
                    .append(relation.joinFrom()).append(" = ").append(relation.range()).append('.')
                    .append(relation.joinTo()).append('\n');
        }
        return text.toString();
    }

    public static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns) {
        return describeJoinHints(snapshot, columns, null);
    }

    /** @param draftedTables 后加表时只提示至少一端是本次新表的连接；为 null 时提示全部。 */
    public static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns, Set<String> draftedTables) {
        List<Concept> concepts = list(snapshot.concepts()).stream()
                .filter(concept -> concept.table() != null && !concept.table().isBlank())
                .toList();
        List<String> lines = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < concepts.size(); i++) {
            for (int j = i + 1; j < concepts.size(); j++) {
                Concept left = concepts.get(i);
                Concept right = concepts.get(j);
                if (draftedTables != null && !draftedTables.contains(left.table().toLowerCase(Locale.ROOT))
                        && !draftedTables.contains(right.table().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> leftColumns = columns.getOrDefault(left.table().toLowerCase(Locale.ROOT), List.of());
                List<String> rightColumns = columns.getOrDefault(right.table().toLowerCase(Locale.ROOT), List.of());
                for (String leftColumn : leftColumns) {
                    for (String rightColumn : rightColumns) {
                        if (sameJoinColumn(leftColumn, rightColumn)) {
                            addJoin(seen, lines, left, leftColumn, right, rightColumn);
                        }
                    }
                }
                addForeignKey(seen, lines, left, leftColumns, right, rightColumns);
                addForeignKey(seen, lines, right, rightColumns, left, leftColumns);
            }
        }
        if (lines.isEmpty()) {
            return "\n列名上没有直接对上的连接。请按列的业务含义继续判断。\n";
        }
        return "\n列名上已经对上的连接，优先写上：\n" + String.join("\n", lines) + "\n";
    }

    public static String describeConceptColumns(Snapshot snapshot, Map<String, List<String>> columns) {
        StringBuilder text = new StringBuilder("\n各概念所在表的列：\n");
        for (Concept concept : list(snapshot.concepts())) {
            if (concept.table() == null || concept.table().isBlank()) {
                continue;
            }
            List<String> names = columns.getOrDefault(concept.table().toLowerCase(Locale.ROOT), List.of());
            text.append(concept.name()).append(" 表=").append(concept.table()).append(" 列=");
            text.append(names.isEmpty() ? "无" : String.join("、", relationColumns(names)));
            text.append('\n');
        }
        return text.toString();
    }

    static List<String> relationColumns(List<String> names) {
        List<String> keys = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String name : names) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.equals("id") || normalized.endsWith("id") || normalized.endsWith("no")
                    || normalized.contains("key") || normalized.contains("code") || normalized.endsWith("name")
                    || normalized.endsWith("type") || normalized.endsWith("status")) {
                keys.add(name);
            } else {
                rest.add(name);
            }
        }
        List<String> ordered = new ArrayList<>(keys);
        ordered.addAll(rest);
        return ordered.size() > 60 ? ordered.subList(0, 60) : ordered;
    }
    public static String conceptTable(Snapshot snapshot, String concept) {
        return snapshot.concepts().stream()
                .filter(item -> item.name().equals(concept))
                .map(Concept::table)
                .findFirst()
                .orElse(null);
    }

    public static List<String> sampleValues(
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            String tableKey,
            String column) {
        if (tableKey == null) {
            return List.of();
        }
        TableSnapshot table = tables.stream().filter(item -> item.key().equalsIgnoreCase(tableKey)).findFirst().orElse(null);
        if (table == null || table.getColumns() == null) {
            return List.of();
        }
        int index = -1;
        List<ColumnSnapshot> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equalsIgnoreCase(column)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (List<Object> row : samples.getOrDefault(table.key(), List.of())) {
            if (index >= row.size() || row.get(index) == null) {
                continue;
            }
            String text = String.valueOf(row.get(index)).trim();
            if (!text.isEmpty() && !values.contains(text)) {
                values.add(text);
            }
        }
        return values;
    }

    static boolean sameJoinColumn(String left, String right) {
        if (left == null || right == null || !left.equalsIgnoreCase(right)) {
            return false;
        }
        String normalized = left.toLowerCase(Locale.ROOT).replace("_", "");
        if (normalized.equals("id") || normalized.equals("time") || normalized.equals("name")
                || normalized.equals("status") || normalized.equals("type") || normalized.equals("code")) {
            return false;
        }
        return normalized.contains("id") || normalized.endsWith("code") || normalized.endsWith("key");
    }

    static void addForeignKey(
            Set<String> seen,
            List<String> lines,
            Concept from,
            List<String> fromColumns,
            Concept to,
            List<String> toColumns) {
        String idColumn = toColumns.stream().filter(column -> column.equalsIgnoreCase("id")).findFirst().orElse(null);
        if (idColumn == null || to.name() == null) {
            return;
        }
        String concept = to.name().toLowerCase(Locale.ROOT);
        String tableLeaf = tableLeaf(to.table());
        for (String column : fromColumns) {
            String stem = idStem(column);
            if (stem.isEmpty()) {
                continue;
            }
            String normalized = stem.toLowerCase(Locale.ROOT);
            if (normalized.equals(concept) || normalized.equals(tableLeaf)
                    || normalized.endsWith(concept) || normalized.endsWith(tableLeaf)) {
                addJoin(seen, lines, from, column, to, idColumn);
            }
        }
    }

    static void addJoin(Set<String> seen, List<String> lines, Concept left, String leftColumn, Concept right, String rightColumn) {
        String key = left.name() + "." + leftColumn + "=" + right.name() + "." + rightColumn;
        if (seen.add(key)) {
            lines.add("domain=" + left.name() + "，range=" + right.name()
                    + "，joinFrom=" + leftColumn + "，joinTo=" + rightColumn);
        }
    }

    static String idStem(String column) {
        String compact = column.replace("_", "");
        if (compact.length() <= 2 || !compact.toLowerCase(Locale.ROOT).endsWith("id")) {
            return "";
        }
        return compact.substring(0, compact.length() - 2);
    }

    static String tableLeaf(String table) {
        if (table == null || table.isBlank()) {
            return "";
        }
        int dot = table.lastIndexOf('.');
        return (dot < 0 ? table : table.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }
    public static String describeTables(List<TableSnapshot> tables, Map<String, List<List<Object>>> samples) {
        StringBuilder text = new StringBuilder();
        for (TableSnapshot table : tables) {
            text.append(describeTable(table, samples.getOrDefault(table.key(), List.of())));
        }
        return text.toString();
    }
    public static String describeExisting(Snapshot existing) {
        if (existing.concepts().isEmpty()) {
            return "已有本体：无\n";
        }
        StringBuilder text = new StringBuilder("已有本体（可直接引用这些类建立关系）：\n");
        Map<String, List<String>> attributesByDomain = existing.attributes().stream()
                .filter(attribute -> attribute.domain() != null)
                .collect(Collectors.groupingBy(Attribute::domain, LinkedHashMap::new,
                        Collectors.mapping(DraftDescriptions::describeAttribute, Collectors.toList())));
        for (Concept concept : existing.concepts()) {
            List<String> attributes = attributesByDomain.getOrDefault(concept.name(), List.of());
            if (attributes.size() > 40) {
                attributes = attributes.subList(0, 40);
            }
            text.append("- ").append(concept.name()).append("（").append(Objects.toString(concept.labelZh(), ""))
                    .append("） 表=").append(Objects.toString(concept.table(), "无"))
                    .append(" 属性=").append(attributes)
                    .append('\n');
        }
        Map<String, List<Code>> codes = list(existing.codes()).stream()
                .filter(code -> code.codeList() != null)
                .collect(Collectors.groupingBy(Code::codeList, LinkedHashMap::new, Collectors.toList()));
        Map<String, String> owners = new LinkedHashMap<>();
        for (Attribute attribute : list(existing.attributes())) {
            if (attribute.codeList() != null) {
                owners.putIfAbsent(attribute.codeList(), attribute.name());
            }
        }
        int shown = 0;
        for (Map.Entry<String, List<Code>> entry : codes.entrySet()) {
            if (shown++ == 12) {
                break;
            }
            text.append("属性 ").append(owners.getOrDefault(entry.getKey(), entry.getKey())).append(" 的码值（取值=中文名）：");
            text.append(entry.getValue().stream().limit(40)
                    .map(code -> code.value() + "=" + Objects.toString(code.labelZh(), code.value()))
                    .collect(Collectors.joining("、")));
            text.append('\n');
        }
        return text.toString();
    }

    /** 事实表的编码列本身不带码值时，码值挂在关系另一端的维表属性上，取值相同，派生条件可以直接用。 */
    public static String describeJoinedCodes(Snapshot snapshot, String concept) {
        StringBuilder text = new StringBuilder();
        for (Relation relation : list(snapshot.relations())) {
            boolean forward = concept.equals(relation.domain());
            if (!forward && !concept.equals(relation.range())) {
                continue;
            }
            String ownColumn = forward ? relation.joinFrom() : relation.joinTo();
            String otherConcept = forward ? relation.range() : relation.domain();
            String otherColumn = forward ? relation.joinTo() : relation.joinFrom();
            Attribute own = attributeOnColumn(snapshot, concept, ownColumn);
            Attribute other = attributeOnColumn(snapshot, otherConcept, otherColumn);
            if (own == null || other == null || other.codeList() == null || other.codeList().isBlank()
                    || (own.codeList() != null && !own.codeList().isBlank())) {
                continue;
            }
            String codes = list(snapshot.codes()).stream()
                    .filter(code -> other.codeList().equals(code.codeList()))
                    .limit(40)
                    .map(code -> code.value() + "=" + Objects.toString(code.labelZh(), code.value()))
                    .collect(Collectors.joining("、"));
            if (!codes.isEmpty()) {
                text.append("属性 ").append(own.name()).append(" 通过关系 ").append(relation.name()).append(" 与 ")
                        .append(other.name()).append(" 取值相同，条件可以直接用这些码值：").append(codes).append('\n');
            }
        }
        return text.toString();
    }

    public static String describeDefined(Snapshot snapshot, String concept) {
        List<DefinedConcept> own = list(snapshot.definedConcepts()).stream()
                .filter(defined -> concept.equals(defined.base()))
                .toList();
        if (own.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n" + concept + " 已有的派生概念，不要重复：\n");
        for (DefinedConcept defined : own) {
            text.append("- ").append(defined.name()).append("（").append(Objects.toString(defined.labelZh(), "")).append("）：")
                    .append(list(defined.conditions()).stream()
                            .map(condition -> condition.attribute() + " " + condition.operator() + " " + condition.value())
                            .collect(Collectors.joining(" 且 ")))
                    .append('\n');
        }
        return text.toString();
    }

    static String joinedCodeList(Snapshot snapshot, Attribute attribute) {
        if (attribute.domain() == null || attribute.column() == null) {
            return null;
        }
        for (Relation relation : list(snapshot.relations())) {
            Attribute other = null;
            if (attribute.domain().equals(relation.domain()) && attribute.column().equalsIgnoreCase(relation.joinFrom())) {
                other = attributeOnColumn(snapshot, relation.range(), relation.joinTo());
            } else if (attribute.domain().equals(relation.range()) && attribute.column().equalsIgnoreCase(relation.joinTo())) {
                other = attributeOnColumn(snapshot, relation.domain(), relation.joinFrom());
            }
            if (other != null && other.codeList() != null && !other.codeList().isBlank()) {
                return other.codeList();
            }
        }
        return null;
    }

    static Attribute attributeOnColumn(Snapshot snapshot, String concept, String column) {
        if (column == null) {
            return null;
        }
        return list(snapshot.attributes()).stream()
                .filter(attribute -> concept.equals(attribute.domain()) && column.equalsIgnoreCase(attribute.column()))
                .findFirst()
                .orElse(null);
    }

    static String describeAttribute(Attribute attribute) {
        StringBuilder text = new StringBuilder(attribute.name()).append("（列 ").append(attribute.column());
        if (attribute.datatype() != null && !attribute.datatype().isBlank()) {
            text.append("，").append(attribute.datatype());
        }
        if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
            text.append("，有码值");
        }
        return text.append('）').toString();
    }

    public static String describeTable(TableSnapshot table, List<List<Object>> samples) {
        StringBuilder text = new StringBuilder();
        text.append("\n表 ").append(table.key()).append("（").append(table.getColumns().size()).append(" 列）\n");
        text.append("列名 | 类型");
        for (int i = 0; i < samples.size(); i++) {
            text.append(" | 样例").append(i + 1);
        }
        text.append('\n');
        List<ColumnSnapshot> columns = table.getColumns();
        for (int c = 0; c < columns.size(); c++) {
            ColumnSnapshot column = columns.get(c);
            text.append(column.getName()).append(" | ").append(column.getType());
            for (List<Object> row : samples) {
                Object cell = c < row.size() ? row.get(c) : null;
                text.append(" | ").append(cell(cell));
            }
            text.append('\n');
        }
        return text.toString();
    }

    public static String describeColumns(TableSnapshot table, List<ColumnSnapshot> columns, List<List<Object>> samples) {
        List<ColumnSnapshot> all = table.getColumns() == null ? List.of() : table.getColumns();
        StringBuilder text = new StringBuilder();
        text.append("\n只为表 ").append(table.key()).append(" 的这些列起草属性：\n");
        text.append("列名 | 类型");
        for (int i = 0; i < samples.size(); i++) {
            text.append(" | 样例").append(i + 1);
        }
        text.append('\n');
        for (ColumnSnapshot column : columns) {
            int index = indexOf(all, column);
            text.append(column.getName()).append(" | ").append(column.getType());
            for (List<Object> row : samples) {
                Object cell = index >= 0 && index < row.size() ? row.get(index) : null;
                text.append(" | ").append(cell(cell));
            }
            text.append('\n');
        }
        return text.toString();
    }

    static int indexOf(List<ColumnSnapshot> columns, ColumnSnapshot target) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(target.getName())) {
                return i;
            }
        }
        return -1;
    }

    static String cell(Object value) {
        if (value == null) {
            return "NULL";
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('|', '/');
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) + "…" : text;
    }

    static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }
}
