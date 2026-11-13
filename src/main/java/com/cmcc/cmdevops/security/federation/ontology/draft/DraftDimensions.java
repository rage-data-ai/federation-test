package com.cmcc.cmdevops.security.federation.ontology.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftResult;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;

public final class DraftDimensions {

    private DraftDimensions() {
    }

    private static final int NARROW_TABLE = 8;

    public static List<String> attachSuggestedCodeLists(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples) {
        List<String> notes = new ArrayList<>();
        for (Attribute attribute : List.copyOf(repository.snapshot().attributes())) {
            if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
                continue;
            }
            String table = DraftDescriptions.conceptTable(repository.snapshot(), attribute.domain());
            List<String> seen = DraftDescriptions.sampleValues(tables, samples, table, attribute.column());
            if (attribute.domain() == null || attribute.column() == null || table == null || table.isBlank()
                    || !DraftHeuristics.codeLike(attribute.column(), seen)) {
                continue;
            }
            if (seen.stream().anyMatch(value -> value.length() > 24)) {
                continue;
            }
            String codeList = OntologyRepository.codeListName(table, attribute.column());
            if (DraftApplier.save(notes, "属性 " + attribute.name(), () -> repository.saveAttribute(withCodeList(attribute, codeList))) > 0) {
                notes.add(attribute.column() + " 标成码值 " + codeList);
            }
        }
        return notes;
    }
    /**
     * 维表由结构决定，不看表名、概念名或列名。
     * 关系的一端列少，并且除连接列外还有更长的文本列；或者一张窄表里有被其他表共用的短编码列，旁边是文本列。
     * 较短的文本列当码值名称，较长的文本列当说明。
     */
    public static List<Dimension> findDimensions(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples) {
        Map<String, List<List<Object>>> rows = samples == null ? Map.of() : samples;
        Map<String, Dimension> found = new LinkedHashMap<>();
        for (Relation relation : DraftApplier.list(snapshot.relations())) {
            addDimensionSide(snapshot, tables, columns, rows, relation.range(), relation.joinTo(), found);
            addDimensionSide(snapshot, tables, columns, rows, relation.domain(), relation.joinFrom(), found);
        }
        for (TableSnapshot table : tables) {
            List<ColumnSnapshot> business = DraftHeuristics.businessColumns(table.getColumns());
            if (business.size() > NARROW_TABLE) {
                continue;
            }
            String code = codeColumn(table, business, snapshot, columns, rows);
            TextPair text = textColumns(table, business, code, rows);
            if (code == null || text.label() == null) {
                continue;
            }
            String concept = OntologyDraftService.conceptForTable(snapshot, table.key());
            if (concept.isBlank()) {
                continue;
            }
            found.putIfAbsent(dimensionKey(concept, code), new Dimension(concept, table.key(), code, text.label(), text.comment()));
        }
        return List.copyOf(found.values());
    }

    public static int loadDimensionCodes(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            OntologyDraftService.DictionaryLookup dictionaries,
            Set<String> covered,
            List<String> warnings) {
        if (dictionaries == null) {
            return 0;
        }
        int saved = 0;
        for (Dimension dimension : findDimensions(repository.snapshot(), tables, columns, samples)) {
            if (!isDrafted(tables, dimension.tableKey())) {
                continue;
            }
            saved += loadDimension(repository, dimension, tables, columns, physicalTypes, dictionaries, covered, warnings);
        }
        return saved;
    }

    public static boolean isDrafted(List<TableSnapshot> tables, String tableKey) {
        return tableKey != null && tables.stream().anyMatch(table -> table.key().equalsIgnoreCase(tableKey));
    }

    /** 从一张维表读出编码和名称，写成它编码列上的码值。 */
    static int loadDimension(
            OntologyRepository repository,
            Dimension dimension,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            OntologyDraftService.DictionaryLookup dictionaries,
            Set<String> covered,
            List<String> warnings) {
        List<List<String>> rows;
        try {
            rows = dictionaries.rows(dimension.tableKey(), dimension.codeColumn(), dimension.labelColumn(), dimension.commentColumn());
        } catch (RuntimeException e) {
            warnings.add("读取维表 " + dimension.tableKey() + "." + dimension.labelColumn() + " 失败：" + e.getMessage());
            return 0;
        }
        if (rows == null || rows.isEmpty()) {
            warnings.add("维表 " + dimension.tableKey() + " 的 " + dimension.labelColumn() + " 列没有读到码值");
            return 0;
        }
        String codeList = bindCodeList(repository, dimension, tables, warnings);
        if (codeList == null) {
            return 0;
        }
        covered.add(codeList);
        List<Code> codes = new ArrayList<>();
        Set<String> seenValues = new LinkedHashSet<>();
        for (List<String> row : rows) {
            if (row == null || row.isEmpty() || row.get(0) == null || row.get(0).isBlank()) {
                continue;
            }
            String value = row.get(0).trim();
            if (value.length() > 64 || !seenValues.add(value)) {
                continue;
            }
            String label = row.size() > 1 && row.get(1) != null ? clip(row.get(1).trim(), 200) : value;
            if (label.isBlank()) {
                label = value;
            }
            String comment = row.size() > 2 && row.get(2) != null ? clip(row.get(2).trim(), 200) : null;
            if (comment != null && comment.isBlank()) {
                comment = null;
            }
            List<String> aliases = comment != null && !comment.equals(label) && comment.length() <= 80 ? List.of(comment) : List.of();
            codes.add(new Code(codeList + "_" + DraftApplier.identifierSuffix(value), codeList, value, label, null, aliases, comment));
        }
        if (codes.isEmpty()) {
            return 0;
        }
        DraftResult applied = DraftApplier.apply(repository,
                new Snapshot(List.of(), List.of(), List.of(), codes, List.of()),
                repository.snapshot(), columns, physicalTypes, null);
        warnings.add("码值集合 " + codeList + " 从维表 " + dimension.tableKey() + " 的 " + dimension.labelColumn()
                + " 列写入 " + applied.codes() + " 个，不再让模型编写中文");
        warnings.addAll(applied.warnings());
        return applied.codes();
    }

    /**
     * 关系建好之后，按关系把码值归到字典一端：关系两端的列取值相同，只留字典概念上那一份。
     * 另一端的列删掉自己的码值，问数时命中码值锁定字典概念，再通过关系连过来。
     * 只处理至少一端是本次起草的概念的关系；已落库的属性也会被修改。
     *
     * @return 被删掉自有码值的属性所属的概念
     */
    public static Set<String> mergeCodesByRelation(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            OntologyDraftService.DictionaryLookup dictionaries,
            List<String> warnings) {
        Map<String, List<List<Object>>> rows = samples == null ? Map.of() : samples;
        Set<String> affected = new LinkedHashSet<>();
        for (Relation relation : List.copyOf(repository.snapshot().relations())) {
            Snapshot snapshot = repository.snapshot();
            if (relation.domain() == null || relation.range() == null
                    || (!isDrafted(tables, DraftDescriptions.conceptTable(snapshot, relation.domain()))
                    && !isDrafted(tables, DraftDescriptions.conceptTable(snapshot, relation.range())))) {
                continue;
            }
            Attribute from = DraftDescriptions.attributeOnColumn(snapshot, relation.domain(), relation.joinFrom());
            Attribute to = DraftDescriptions.attributeOnColumn(snapshot, relation.range(), relation.joinTo());
            if (from == null || to == null) {
                continue;
            }
            Dimension rangeSide = dimensionSide(snapshot, tables, columns, rows, relation.range(), relation.joinTo());
            Dimension domainSide = dimensionSide(snapshot, tables, columns, rows, relation.domain(), relation.joinFrom());
            if ((rangeSide == null) == (domainSide == null)) {
                if (rangeSide != null && hasCodes(snapshot, from) && hasCodes(snapshot, to)) {
                    warnings.add("关系 " + relation.name() + " 两端都像字典表，" + from.name() + " 和 " + to.name()
                            + " 各自的码值没有归并，请人工确认留哪一份");
                }
                continue;
            }
            Dimension dictionary = rangeSide != null ? rangeSide : domainSide;
            String ownerName = (rangeSide != null ? to : from).name();
            String factName = (rangeSide != null ? from : to).name();
            if (!hasCodes(snapshot, attributeNamed(snapshot, ownerName)) && dictionaries != null
                    && isDrafted(tables, dictionary.tableKey())) {
                loadDimension(repository, dictionary, tables, columns, physicalTypes, dictionaries, new LinkedHashSet<>(), warnings);
                snapshot = repository.snapshot();
            }
            Attribute owner = attributeNamed(snapshot, ownerName);
            Attribute fact = attributeNamed(snapshot, factName);
            if (owner == null || fact == null || !hasCodes(snapshot, owner)
                    || fact.codeList() == null || fact.codeList().isBlank() || fact.codeList().equals(owner.codeList())) {
                continue;
            }
            Set<String> known = new LinkedHashSet<>();
            for (Code code : DraftApplier.list(snapshot.codes())) {
                if (owner.codeList().equals(code.codeList()) && code.value() != null) {
                    known.add(code.value().trim());
                }
            }
            List<String> own = DraftApplier.list(snapshot.codes()).stream()
                    .filter(code -> fact.codeList().equals(code.codeList()) && code.value() != null)
                    .map(code -> code.value().trim())
                    .toList();
            List<String> missing = own.stream().filter(value -> !known.contains(value)).toList();
            if (DraftApplier.save(warnings, "属性 " + fact.name(), () -> repository.saveAttribute(withCodeList(fact, null))) == 0) {
                continue;
            }
            affected.add(fact.domain());
            warnings.add(fact.domain() + "." + fact.column() + " 通过关系 " + relation.name() + " 连到字典概念 " + owner.domain()
                    + "，码值只留在 " + owner.name() + " 上，已删除它自己的 " + own.size() + " 个码值"
                    + (missing.isEmpty() ? "" : "；其中字典里没有的取值：" + String.join("、", missing)));
        }
        return affected;
    }

    static Dimension dimensionSide(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples,
            String concept,
            String joinColumn) {
        Map<String, Dimension> found = new LinkedHashMap<>();
        addDimensionSide(snapshot, tables, columns, samples, concept, joinColumn, found);
        return found.isEmpty() ? null : found.values().iterator().next();
    }

    static Attribute attributeNamed(Snapshot snapshot, String name) {
        return DraftApplier.list(snapshot.attributes()).stream().filter(attribute -> attribute.name().equals(name)).findFirst().orElse(null);
    }

    static boolean hasCodes(Snapshot snapshot, Attribute attribute) {
        return attribute != null && attribute.codeList() != null && !attribute.codeList().isBlank()
                && DraftApplier.list(snapshot.codes()).stream().anyMatch(code -> attribute.codeList().equals(code.codeList()));
    }

    /**
     * 维表码值只挂在维表概念自己的编码列上。事实表里同名的编码列不再另建码值，
     * 问数时命中码值就锁定维表概念，再通过关系连到事实表。
     */
    static String bindCodeList(
            OntologyRepository repository,
            Dimension dimension,
            List<TableSnapshot> tables,
            List<String> warnings) {
        Snapshot snapshot = repository.snapshot();
        Attribute own = null;
        for (Attribute attribute : snapshot.attributes()) {
            if (dimension.concept().equals(attribute.domain())
                    && attribute.column() != null
                    && attribute.column().equalsIgnoreCase(dimension.codeColumn())) {
                own = attribute;
                break;
            }
        }
        if (own == null) {
            warnings.add("跳过维表 " + dimension.tableKey() + "：概念 " + dimension.concept() + " 没有映射 "
                    + dimension.codeColumn() + " 列的属性，码值无法归到概念上");
            return null;
        }
        String table = DraftDescriptions.conceptTable(snapshot, dimension.concept());
        String codeList = OntologyRepository.codeListName(table == null || table.isBlank() ? dimension.tableKey() : table,
                own.column());
        if (!codeList.equals(own.codeList())) {
            Attribute target = own;
            if (DraftApplier.save(warnings, "属性 " + own.name(), () -> repository.saveAttribute(withCodeList(target, codeList))) == 0) {
                return null;
            }
        }
        Set<String> drafted = new LinkedHashSet<>();
        tables.forEach(item -> drafted.add(item.key().toLowerCase(Locale.ROOT)));
        for (Attribute attribute : List.copyOf(repository.snapshot().attributes())) {
            if (dimension.concept().equals(attribute.domain())
                    || attribute.column() == null
                    || !attribute.column().equalsIgnoreCase(dimension.codeColumn())
                    || attribute.codeList() == null
                    || attribute.codeList().isBlank()) {
                continue;
            }
            String otherTable = DraftDescriptions.conceptTable(snapshot, attribute.domain());
            if (otherTable == null || !drafted.contains(otherTable.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (DraftApplier.save(warnings, "属性 " + attribute.name(), () -> repository.saveAttribute(withCodeList(attribute, null))) > 0) {
                warnings.add(attribute.domain() + "." + attribute.column() + " 的含义来自维表 " + dimension.tableKey()
                        + "（概念 " + dimension.concept() + "），不再单独建码值，问数时通过关系连到维表");
            }
        }
        return codeList;
    }

    static Attribute withCodeList(Attribute attribute, String codeList) {
        return new Attribute(attribute.name(), attribute.labelZh(), attribute.labelEn(), attribute.aliases(),
                attribute.comment(), attribute.domain(), attribute.datatype(), attribute.column(),
                attribute.unit(), attribute.identifier(), codeList);
    }

    static void addDimensionSide(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples,
            String concept,
            String joinColumn,
            Map<String, Dimension> found) {
        if (concept == null || joinColumn == null || joinColumn.isBlank()) {
            return;
        }
        String tableKey = DraftDescriptions.conceptTable(snapshot, concept);
        if (tableKey == null) {
            return;
        }
        List<String> names = columns.getOrDefault(tableKey.toLowerCase(Locale.ROOT), List.of());
        if (names.isEmpty() || names.size() > NARROW_TABLE || names.stream().noneMatch(name -> name.equalsIgnoreCase(joinColumn))) {
            return;
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Attribute attribute : DraftApplier.list(snapshot.attributes())) {
            if (concept.equals(attribute.domain()) && attribute.column() != null && attribute.datatype() != null) {
                types.put(attribute.column().toLowerCase(Locale.ROOT), attribute.datatype());
            }
        }
        TableSnapshot table = tableOrNames(tables, tableKey, names, types);
        List<ColumnSnapshot> business = DraftHeuristics.businessColumns(table.getColumns());
        String code = business.stream().map(ColumnSnapshot::getName).filter(name -> name.equalsIgnoreCase(joinColumn)).findFirst().orElse(joinColumn);
        TextPair text = textColumns(table, business, code, samples);
        if (text.label() == null) {
            return;
        }
        found.putIfAbsent(dimensionKey(concept, code), new Dimension(concept, tableKey, code, text.label(), text.comment()));
    }

    static String codeColumn(
            TableSnapshot table,
            List<ColumnSnapshot> business,
            Snapshot snapshot,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples) {
        String shared = null;
        String fallback = null;
        for (ColumnSnapshot column : business) {
            List<String> seen = DraftDescriptions.sampleValues(List.of(table), samples, table.key(), column.getName());
            if (isText(column, seen)) {
                continue;
            }
            if (sharedWithOther(column.getName(), table.key(), snapshot, columns)) {
                shared = column.getName();
            } else if (fallback == null && (numericType(column) || DraftHeuristics.codeLike(column.getName(), seen) || !seen.isEmpty())) {
                fallback = column.getName();
            }
        }
        return shared != null ? shared : fallback;
    }

    static TextPair textColumns(
            TableSnapshot table,
            List<ColumnSnapshot> business,
            String code,
            Map<String, List<List<Object>>> samples) {
        if (code == null) {
            return new TextPair(null, null);
        }
        List<ColumnSnapshot> texts = new ArrayList<>();
        for (ColumnSnapshot column : business) {
            if (column.getName().equalsIgnoreCase(code) || DraftHeuristics.technicalColumn(column.getName())) {
                continue;
            }
            List<String> seen = DraftDescriptions.sampleValues(List.of(table), samples, table.key(), column.getName());
            if (isText(column, seen)) {
                texts.add(column);
            }
        }
        if (texts.isEmpty()) {
            return new TextPair(null, null);
        }
        texts.sort((left, right) -> Integer.compare(averageLength(table, left.getName(), samples), averageLength(table, right.getName(), samples)));
        String label = texts.get(0).getName();
        String comment = texts.size() > 1 ? texts.get(texts.size() - 1).getName() : null;
        if (comment != null && comment.equalsIgnoreCase(label)) {
            comment = null;
        }
        return new TextPair(label, comment);
    }

    static int averageLength(TableSnapshot table, String column, Map<String, List<List<Object>>> samples) {
        List<String> seen = DraftDescriptions.sampleValues(List.of(table), samples, table.key(), column);
        if (seen.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return seen.stream().mapToInt(String::length).sum() / seen.size();
    }

    static boolean isText(ColumnSnapshot column, List<String> samples) {
        if (!samples.isEmpty()) {
            boolean prose = samples.stream().anyMatch(DraftDimensions::prose);
            boolean code = samples.stream().allMatch(DraftDimensions::shortToken);
            return prose && !code;
        }
        String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
        return type.contains("char") || type.contains("string") || type.contains("text");
    }

    static boolean prose(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 8) {
            return true;
        }
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    static boolean shortToken(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 8 && text.matches("[A-Za-z0-9_\\-]+");
    }

    static boolean numericType(ColumnSnapshot column) {
        String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
        return type.contains("int") || type.contains("number") || type.contains("decimal")
                || type.contains("numeric") || type.contains("double") || type.contains("float");
    }

    static boolean sharedWithOther(String column, String tableKey, Snapshot snapshot, Map<String, List<String>> columns) {
        for (Concept concept : DraftApplier.list(snapshot.concepts())) {
            if (concept.table() == null || concept.table().equalsIgnoreCase(tableKey)) {
                continue;
            }
            List<String> others = columns.getOrDefault(concept.table().toLowerCase(Locale.ROOT), List.of());
            if (others.stream().anyMatch(other -> other.equalsIgnoreCase(column))) {
                return true;
            }
        }
        return false;
    }

    /** 本次没勾选的表没有列类型，用已有属性的数据类型代替，才能认出它的文本列。 */
    static TableSnapshot tableOrNames(List<TableSnapshot> tables, String tableKey, List<String> names,
                                              Map<String, String> types) {
        for (TableSnapshot table : tables) {
            if (table.key().equalsIgnoreCase(tableKey)) {
                return table;
            }
        }
        TableSnapshot synthetic = new TableSnapshot();
        String[] parts = tableKey.split("\\.", 3);
        if (parts.length == 3) {
            synthetic.setCatalog(parts[0]);
            synthetic.setSchema(parts[1]);
            synthetic.setTable(parts[2]);
        }
        synthetic.setColumns(names.stream()
                .map(name -> new ColumnSnapshot(name, types.getOrDefault(name.toLowerCase(Locale.ROOT), "")))
                .toList());
        return synthetic;
    }

    static String dimensionKey(String concept, String column) {
        return concept + "|" + column.toLowerCase(Locale.ROOT);
    }

    static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    public record Dimension(String concept, String tableKey, String codeColumn, String labelColumn, String commentColumn) {
    }

    private record TextPair(String label, String comment) {
    }
}
