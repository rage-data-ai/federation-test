package com.cmcc.cmdevops.security.federation.ontology.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftResult;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary;

public final class DraftApplier {

    private DraftApplier() {
    }

    static String physicalType(Map<String, Map<String, String>> types, String table, String column) {
        if (types == null || table == null || column == null) {
            return null;
        }
        Map<String, String> columns = types.get(table.toLowerCase(Locale.ROOT));
        return columns == null ? null : columns.get(column.toLowerCase(Locale.ROOT));
    }
    public static DraftResult apply(
            OntologyRepository repository,
            Snapshot draft,
            Snapshot existing,
            Map<String, List<String>> tableColumns) {
        return apply(repository, draft, existing, tableColumns, Map.of(), null);
    }

    public static DraftResult apply(
            OntologyRepository repository,
            Snapshot draft,
            Snapshot existing,
            Map<String, List<String>> tableColumns,
            Map<String, Map<String, String>> physicalTypes,
            Map<String, Set<String>> allowedCodes) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> conceptTables = new LinkedHashMap<>();
        existing.concepts().forEach(concept -> conceptTables.put(concept.name(), concept.table()));
        // 实体名全局唯一：起名查重要看全部主题域，引用解析只看 existing（本主题域）
        Snapshot everything = repository.all();
        Map<String, String> takenConcepts = new LinkedHashMap<>();
        everything.concepts().forEach(concept -> takenConcepts.put(concept.name(), concept.table()));

        Map<String, String> renames = new LinkedHashMap<>();
        List<Concept> concepts = new ArrayList<>();
        for (Concept concept : list(draft.concepts())) {
            String table = concept.table();
            if (table != null && !table.isBlank() && !tableColumns.containsKey(table.toLowerCase(Locale.ROOT))) {
                warnings.add("跳过类 " + concept.name() + "：表 " + table + " 不在本次勾选范围内");
                continue;
            }
            String name = concept.name();
            List<String> aliases = new ArrayList<>(list(concept.aliases()));
            if (table != null && !table.isBlank()) {
                String owner = conceptOfTable(conceptTables, table);
                String generated = OntologyRepository.conceptName(table, takenConcepts);
                String elsewhere = owner == null ? conceptOfTable(takenConcepts, table) : null;
                if (elsewhere != null) {
                    warnings.add("跳过类 " + concept.name() + "：表 " + table + " 已经是别的主题域里的概念 " + elsewhere
                            + "，一张表只能属于一个主题域");
                    continue;
                }
                if (owner != null) {
                    if (concept.name() != null) {
                        renames.put(concept.name(), owner);
                    }
                    if (!owner.equals(generated)) {
                        warnings.add("跳过类 " + concept.name() + "：表 " + table + " 已有概念 " + owner
                                + "（旧命名），一张表只能有一个概念；要改成 " + generated + " 请先删除 " + owner + " 再起草");
                        continue;
                    }
                    if (concepts.stream().anyMatch(item -> item.name().equals(owner))) {
                        warnings.add("跳过类 " + concept.name() + "：表 " + table + " 已经写成概念 " + owner);
                        continue;
                    }
                }
                name = generated;
                if (concept.name() != null && !concept.name().isBlank() && !concept.name().equals(name)) {
                    renames.put(concept.name(), name);
                    if (aliases.stream().noneMatch(concept.name()::equalsIgnoreCase)) {
                        aliases.add(concept.name());
                    }
                }
            }
            String timeColumn = matchColumn(tableColumns, table, concept.timeColumn());
            boolean large = concept.largeTable();
            if (large && timeColumn == null) {
                warnings.add("类 " + name + " 是大表但没有可用的时间列，已取消大表标记，请人工补充时间列");
                large = false;
            }
            concepts.add(new Concept(name, concept.labelZh(), concept.labelEn(), aliases,
                    concept.comment(), concept.parent(), table, timeColumn, large));
            conceptTables.put(name, table);
            takenConcepts.put(name, table);
        }
        ConceptResolver resolve = reference -> resolveConcept(reference, conceptTables, renames, existing);
        concepts = concepts.stream()
                .map(concept -> new Concept(concept.name(), concept.labelZh(), concept.labelEn(), concept.aliases(),
                        concept.comment(), resolve.apply(concept.parent()), concept.table(), concept.timeColumn(),
                        concept.largeTable()))
                .toList();
        int savedConcepts = saveInDependencyOrder(repository, concepts, warnings);

        int savedAttributes = 0;
        Map<String, String> requestedLists = new LinkedHashMap<>();
        Map<String, String> attributeRenames = new LinkedHashMap<>();
        Set<String> columnsInBatch = new LinkedHashSet<>();
        for (Attribute drafted : list(draft.attributes())) {
            Attribute attribute = withDomain(drafted, resolve.apply(drafted.domain()));
            String table = conceptTables.get(attribute.domain());
            String column = matchColumn(tableColumns, table, attribute.column());
            if (attribute.domain() == null || !conceptTables.containsKey(attribute.domain())) {
                warnings.add("跳过属性 " + attribute.name() + "：domain " + attribute.domain() + " 不存在");
                continue;
            }
            if (attribute.column() != null && column == null) {
                warnings.add("跳过属性 " + attribute.name() + "：列 " + attribute.column() + " 不在表 " + table + " 中");
                continue;
            }
            String name = attribute.name();
            List<String> aliases = new ArrayList<>(list(attribute.aliases()));
            if (column != null) {
                String generated = OntologyRepository.attributeName(attribute.domain(), column);
                String domain = attribute.domain();
                Optional<Attribute> legacy = list(existing.attributes()).stream()
                        .filter(other -> domain.equals(other.domain()) && column.equalsIgnoreCase(other.column())
                                && !other.name().equals(generated))
                        .findFirst();
                if (legacy.isPresent()) {
                    attributeRenames.put(attribute.name(), legacy.get().name());
                    warnings.add("跳过属性 " + attribute.name() + "：" + domain + " 的列 " + column + " 已有属性 "
                            + legacy.get().name() + "（旧命名）；要改成 " + generated + " 请先删除它再起草");
                    continue;
                }
                if (!columnsInBatch.add(generated)) {
                    warnings.add("跳过属性 " + attribute.name() + "：" + domain + " 的列 " + column + " 本次已写成属性 " + generated);
                    continue;
                }
                if (name != null && !name.isBlank() && !name.equals(generated)) {
                    attributeRenames.put(name, generated);
                    if (aliases.stream().noneMatch(name::equalsIgnoreCase)) {
                        aliases.add(name);
                    }
                }
                name = generated;
            }
            String datatype = DraftHeuristics.normalizeDatatype(attribute.datatype(), physicalType(physicalTypes, table, column));
            Attribute fixed = new Attribute(name, attribute.labelZh(), attribute.labelEn(), aliases,
                    attribute.comment(), attribute.domain(), datatype, column, attribute.unit(),
                    attribute.identifier(), attribute.codeList());
            int saved = save(warnings, "属性 " + name, () -> repository.saveAttribute(fixed));
            savedAttributes += saved;
            if (saved > 0 && attribute.codeList() != null && !attribute.codeList().isBlank()) {
                String canonical = repository.codeListFor(fixed);
                requestedLists.put(attribute.codeList().trim().toLowerCase(Locale.ROOT), canonical);
                if (!canonical.equals(attribute.codeList().trim())) {
                    warnings.add("属性 " + attribute.name() + " 的码值集合按表和列命名为 " + canonical);
                }
            }
        }

        int savedCodes = 0;
        if (!list(draft.codes()).isEmpty()) {
            Snapshot current = repository.snapshot();
            Snapshot global = repository.all();
            Map<String, String> lists = codeListAliases(current, requestedLists);
            Set<String> taken = occupiedNames(new Snapshot(global.concepts(), global.attributes(), global.relations(),
                    List.of(), global.definedConcepts()));
            Map<String, String> codeOwners = new LinkedHashMap<>();
            Map<String, String> existingByValue = new LinkedHashMap<>();
            for (Code code : list(global.codes())) {
                codeOwners.put(code.name(), code.codeList());
            }
            for (Code code : list(current.codes())) {
                if (code.value() != null) {
                    existingByValue.putIfAbsent(code.codeList() + "\u0000" + code.value().trim(), code.name());
                }
            }
            Set<String> batch = new LinkedHashSet<>();
            for (Code code : list(draft.codes())) {
                String codeList = code.codeList() == null ? null : lists.get(code.codeList().trim().toLowerCase(Locale.ROOT));
                if (codeList == null) {
                    warnings.add("跳过码值 " + code.name() + "：码值集合 " + code.codeList() + " 没有对应的属性");
                    continue;
                }
                Set<String> allowed = allowedCodes == null ? null : allowedCodes.get(codeList);
                if (allowed != null && (code.value() == null || !allowed.contains(code.value().trim()))) {
                    warnings.add("跳过码值 " + code.name() + "：取值 " + code.value() + " 不在读到的数据里");
                    continue;
                }
                String value = code.value() == null ? null : code.value().trim();
                String name = existingByValue.get(codeList + "\u0000" + value);
                if (name == null || batch.contains(name)) {
                    Set<String> used = new LinkedHashSet<>(taken);
                    used.addAll(batch);
                    codeOwners.forEach((other, owner) -> {
                        if (!codeList.equals(owner)) {
                            used.add(other);
                        }
                    });
                    String preferred = codeList + "_" + identifierSuffix(value);
                    name = codeIndividualName(preferred, codeList, value, used);
                    if (!name.equals(preferred)) {
                        warnings.add("码值名称 " + preferred + " 与已有实体冲突，改为 " + name);
                    }
                }
                batch.add(name);
                Code fixed = new Code(name, codeList, code.value(), code.labelZh(), code.labelEn(), code.aliases(), code.comment());
                savedCodes += save(warnings, "码值 " + name, () -> repository.saveCode(fixed));
            }
        }

        int savedRelations = 0;
        Map<String, Relation> knownRelations = new LinkedHashMap<>();
        list(everything.relations()).forEach(relation -> knownRelations.put(relation.name(), relation));
        for (Relation drafted : list(draft.relations())) {
            Relation relation = new Relation(drafted.name(), drafted.labelZh(), drafted.labelEn(), drafted.aliases(),
                    drafted.comment(), resolve.apply(drafted.domain()), resolve.apply(drafted.range()),
                    drafted.joinFrom(), drafted.joinTo(), drafted.inverse(), drafted.transitive(), drafted.functional());
            String outside = outsideSubject(conceptTables, takenConcepts, relation.domain(), relation.range());
            if (outside != null) {
                warnings.add("跳过关系 " + relation.name() + "：概念 " + outside + " 在别的主题域，只有同主题域的概念能建立关系");
                continue;
            }
            String from = matchColumn(tableColumns, conceptTables.get(relation.domain()), relation.joinFrom());
            String to = matchColumn(tableColumns, conceptTables.get(relation.range()), relation.joinTo());
            if (from == null || to == null) {
                warnings.add("跳过关系 " + relation.name() + "：Join 列 " + relation.joinFrom() + " / " + relation.joinTo()
                        + " 在 " + relation.domain() + " / " + relation.range() + " 的表中找不到");
                continue;
            }
            if (qualified(relation.joinFrom()) || qualified(relation.joinTo())) {
                warnings.add("关系 " + relation.name() + " 的连接列已按列名 " + from + " / " + to + " 写入");
            }
            Relation same = knownRelations.values().stream()
                    .filter(other -> sameJoin(other, relation.domain(), relation.range(), from, to))
                    .findFirst().orElse(null);
            if (same != null && !same.name().equals(relation.name())) {
                warnings.add("跳过关系 " + relation.name() + "：" + relation.domain() + "." + from + " = " + relation.range()
                        + "." + to + " 已有关系 " + same.name());
                continue;
            }
            String name = relation.name();
            Relation clash = name == null ? null : knownRelations.get(name);
            if (clash != null && !(Objects.equals(clash.domain(), relation.domain())
                    && Objects.equals(clash.range(), relation.range()))) {
                String renamed = lowerFirst(relation.domain()) + "_" + name;
                if (knownRelations.containsKey(renamed)) {
                    renamed = renamed + "_" + relation.range();
                }
                warnings.add("关系名 " + name + " 已用于 " + clash.domain() + " → " + clash.range() + "，本条改名为 " + renamed);
                name = renamed;
            }
            Relation fixed = new Relation(name, relation.labelZh(), relation.labelEn(), relation.aliases(),
                    relation.comment(), relation.domain(), relation.range(), from, to, relation.inverse(),
                    relation.transitive(), relation.functional());
            int saved = save(warnings, "关系 " + name, () -> repository.saveRelation(fixed));
            savedRelations += saved;
            if (saved > 0) {
                knownRelations.put(name, fixed);
            }
        }

        int savedDefined = 0;
        Snapshot afterAttributes = list(draft.definedConcepts()).isEmpty() ? existing : repository.snapshot();
        for (DefinedConcept drafted : list(draft.definedConcepts())) {
            String base = resolve.apply(drafted.base());
            if (outsideSubject(conceptTables, takenConcepts, base) != null) {
                warnings.add("跳过派生概念 " + drafted.name() + "：基类 " + base + " 在别的主题域");
                continue;
            }
            List<Condition> conditions = list(drafted.conditions()).stream()
                    .map(condition -> fixCondition(drafted.name(), base, condition, afterAttributes, attributeRenames, warnings))
                    .toList();
            DefinedConcept twin = list(afterAttributes.definedConcepts()).stream()
                    .filter(other -> !other.name().equals(drafted.name()) && Objects.equals(other.base(), base)
                            && !conditions.isEmpty() && new LinkedHashSet<>(list(other.conditions())).equals(new LinkedHashSet<>(conditions)))
                    .findFirst().orElse(null);
            if (twin != null) {
                warnings.add("跳过派生概念 " + drafted.name() + "：条件和已有派生概念 " + twin.name() + " 相同");
                continue;
            }
            String definedName = drafted.name();
            DefinedConcept clash = list(everything.definedConcepts()).stream()
                    .filter(other -> other.name().equals(drafted.name()) && !Objects.equals(other.base(), base))
                    .findFirst().orElse(null);
            if (clash != null && base != null) {
                definedName = base + "_" + drafted.name();
                warnings.add("派生概念名 " + drafted.name() + " 已用于 " + clash.base() + "，本条改名为 " + definedName);
            }
            DefinedConcept defined = new DefinedConcept(definedName, drafted.labelZh(), drafted.labelEn(),
                    drafted.aliases(), drafted.comment(), base, conditions, drafted.definitionText());
            savedDefined += save(warnings, "派生概念 " + defined.name(), () -> repository.saveDefinedConcept(defined));
        }
        return new DraftResult(savedConcepts, savedAttributes, savedRelations, savedCodes, savedDefined, warnings);
    }

    /** 引用的概念不在本主题域、却在别的主题域里时返回它的名字。 */
    static String outsideSubject(Map<String, String> inSubject, Map<String, String> everywhere, String... concepts) {
        for (String concept : concepts) {
            if (concept != null && !inSubject.containsKey(concept) && everywhere.containsKey(concept)) {
                return concept;
            }
        }
        return null;
    }

    /** 两端概念和连接列都相同就是同一条连接，方向反过来也算。 */
    static boolean sameJoin(Relation other, String domain, String range, String from, String to) {
        if (other.joinFrom() == null || other.joinTo() == null) {
            return false;
        }
        boolean forward = Objects.equals(other.domain(), domain) && Objects.equals(other.range(), range)
                && other.joinFrom().equalsIgnoreCase(from) && other.joinTo().equalsIgnoreCase(to);
        boolean backward = Objects.equals(other.domain(), range) && Objects.equals(other.range(), domain)
                && other.joinFrom().equalsIgnoreCase(to) && other.joinTo().equalsIgnoreCase(from);
        return forward || backward;
    }

    static String lowerFirst(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 模型常把条件写成列名（LogonType）或码值中文名（登录失败）。
     * 按基础概念的属性把列名换成属性名，把中文名换成码值取值。
     */
    public static Condition fixCondition(String definedName, String base, Condition condition, Snapshot snapshot,
                                  Map<String, String> renames, List<String> warnings) {
        String written = condition.attribute();
        if (written == null || written.isBlank()) {
            return condition;
        }
        String reference = renames.getOrDefault(written, written);
        List<Attribute> attributes = list(snapshot.attributes());
        List<Attribute> own = attributes.stream().filter(a -> Objects.equals(a.domain(), base)).toList();
        Attribute target = attributes.stream().filter(a -> a.name().equals(reference)).findFirst()
                .or(() -> own.stream().filter(a -> a.name().equalsIgnoreCase(reference)).findFirst())
                .or(() -> own.stream().filter(a -> reference.equalsIgnoreCase(a.column())).findFirst())
                .or(() -> own.stream().filter(a -> list(a.aliases()).stream().anyMatch(reference::equalsIgnoreCase)).findFirst())
                .or(() -> attributes.stream().filter(a -> a.name().equalsIgnoreCase(reference)).findFirst())
                .orElse(null);
        if (target == null) {
            return condition;
        }
        if (!target.name().equals(written)) {
            warnings.add("派生概念 " + definedName + " 的条件 " + written + " 改为属性 " + target.name());
        }
        String value = condition.value();
        String codeList = target.codeList() != null && !target.codeList().isBlank()
                ? target.codeList() : DraftDescriptions.joinedCodeList(snapshot, target);
        if (value != null && codeList != null) {
            String trimmed = value.trim();
            List<Code> codes = list(snapshot.codes()).stream()
                    .filter(code -> codeList.equals(code.codeList()))
                    .toList();
            boolean isValue = codes.stream().anyMatch(code -> trimmed.equals(code.value()));
            Optional<Code> byLabel = codes.stream()
                    .filter(code -> trimmed.equals(code.labelZh()) || list(code.aliases()).contains(trimmed))
                    .findFirst();
            if (!isValue && byLabel.isPresent()) {
                warnings.add("派生概念 " + definedName + " 的条件值 " + value + " 改为码值 " + byLabel.get().value());
                value = byLabel.get().value();
            }
        }
        return new Condition(target.name(), condition.operator(), value);
    }

    @FunctionalInterface
    private interface ConceptResolver {
        String apply(String reference);
    }

    static String conceptOfTable(Map<String, String> conceptTables, String table) {
        return conceptTables.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().equalsIgnoreCase(table.trim()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** 概念名由表名生成，模型仍可能用它自己起的名字引用概念，按本批改名和概念别名找回。 */
    static String resolveConcept(
            String reference,
            Map<String, String> conceptTables,
            Map<String, String> renames,
            Snapshot existing) {
        if (reference == null || reference.isBlank() || conceptTables.containsKey(reference)) {
            return reference;
        }
        String renamed = renames.get(reference);
        if (renamed != null) {
            return renamed;
        }
        return list(existing.concepts()).stream()
                .filter(concept -> list(concept.aliases()).stream().anyMatch(reference::equalsIgnoreCase))
                .map(Concept::name)
                .findFirst()
                .orElse(reference);
    }

    static Attribute withDomain(Attribute attribute, String domain) {
        return new Attribute(attribute.name(), attribute.labelZh(), attribute.labelEn(), attribute.aliases(),
                attribute.comment(), domain, attribute.datatype(), attribute.column(),
                attribute.unit(), attribute.identifier(), attribute.codeList());
    }

    static int saveInDependencyOrder(OntologyRepository repository, List<Concept> concepts, List<String> warnings) {
        List<Concept> pending = new ArrayList<>(concepts);
        int saved = 0;
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                Concept concept = iterator.next();
                try {
                    repository.saveConcept(concept);
                    iterator.remove();
                    saved++;
                    progress = true;
                } catch (IllegalArgumentException e) {
                    // 父类可能排在后面，下一轮重试
                }
            }
        }
        for (Concept concept : pending) {
            save(warnings, "类 " + concept.name(), () -> repository.saveConcept(concept));
        }
        return saved;
    }

    static int save(List<String> warnings, String what, Runnable action) {
        try {
            action.run();
            return 1;
        } catch (IllegalArgumentException e) {
            warnings.add("跳过" + what + "：" + e.getMessage());
            return 0;
        }
    }

    static String matchColumn(Map<String, List<String>> tableColumns, String table, String column) {
        if (table == null || column == null || column.isBlank()) {
            return null;
        }
        List<String> columns = tableColumns.get(table.toLowerCase(Locale.ROOT));
        if (columns == null) {
            return null;
        }
        String raw = column.trim();
        String match = findColumn(columns, raw);
        if (match != null) {
            return match;
        }
        int dot = Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('．'));
        if (dot >= 0 && dot < raw.length() - 1) {
            return findColumn(columns, raw.substring(dot + 1).trim());
        }
        return null;
    }

    static String findColumn(List<String> columns, String name) {
        return columns.stream().filter(column -> column.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    static boolean qualified(String column) {
        return column != null && (column.indexOf('.') >= 0 || column.indexOf('．') >= 0);
    }

    /** 模型写的码值集合名、属性名或本批属性里填的旧名字，都换成属性上真正挂着的集合名。 */
    static Map<String, String> codeListAliases(Snapshot current, Map<String, String> requestedLists) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (Attribute attribute : list(current.attributes())) {
            if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
                aliases.put(attribute.name().toLowerCase(Locale.ROOT), attribute.codeList());
            }
        }
        for (Attribute attribute : list(current.attributes())) {
            if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
                aliases.put(attribute.codeList().toLowerCase(Locale.ROOT), attribute.codeList());
            }
        }
        aliases.putAll(requestedLists);
        return aliases;
    }

    static Set<String> occupiedNames(Snapshot existing) {
        Set<String> used = new LinkedHashSet<>();
        list(existing.concepts()).forEach(concept -> used.add(concept.name()));
        list(existing.attributes()).forEach(attribute -> {
            used.add(attribute.name());
            if (attribute.codeList() != null) {
                used.add(attribute.codeList());
            }
        });
        list(existing.relations()).forEach(relation -> used.add(relation.name()));
        list(existing.codes()).forEach(code -> {
            used.add(code.name());
            if (code.codeList() != null) {
                used.add(code.codeList());
            }
        });
        list(existing.definedConcepts()).forEach(defined -> used.add(defined.name()));
        used.remove(null);
        return used;
    }

    public static String codeIndividualName(String requested, String codeList, String value, Set<String> used) {
        String candidate = requested;
        if (!usableName(candidate) || used.contains(candidate) || candidate.equalsIgnoreCase(codeList)) {
            candidate = (codeList == null ? "code" : codeList) + "_" + identifierSuffix(value);
        }
        if (!usableName(candidate) || candidate.equalsIgnoreCase(codeList)) {
            candidate = (codeList == null ? "code" : codeList) + "_value";
        }
        String name = candidate;
        int suffix = 2;
        while (used.contains(name) || name.equalsIgnoreCase(codeList)) {
            name = candidate + "_" + suffix++;
        }
        return name;
    }

    static boolean usableName(String name) {
        return name != null && OntologyVocabulary.NAME.matcher(name).matches();
    }

    static String identifierSuffix(String value) {
        if (value == null) {
            return "value";
        }
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < value.length() && suffix.length() < 40; i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                suffix.append(c);
            }
        }
        return suffix.isEmpty() ? "value" : suffix.toString();
    }
    static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }
}
