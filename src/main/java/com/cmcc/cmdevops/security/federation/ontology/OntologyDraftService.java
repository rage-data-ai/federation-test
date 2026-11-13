package com.cmcc.cmdevops.security.federation.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.llm.JsonReply;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.MetadataStore;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OntologyDraftService {

    private static final int MAX_CELL = 40;
    private static final int ATTRIBUTE_BATCH = 10;
    private static final int DEFINED_PER_CONCEPT = 8;

    private static final String STAGE_RULES = """
            你是数据本体建模专家。根据表名、列名、样例和补充说明判断业务，不要套用与当前表无关的行业。
            只输出一个完整闭合的 JSON 对象，不要输出其他文字。
            comment 不超过 40 个字，不要罗列取值清单。name 只能用字母开头的字母、数字、下划线。
            类用 PascalCase，属性和关系用 camelCase。属性名由系统按"概念名_列名"生成，例如 order_status；
            起草属性时 name 写一个英文业务名即可，会留作别名。引用已有属性时写已有本体里的属性名。
            datatype 只能取 string、integer、long、decimal、double、boolean、date、dateTime。
            已有本体里的类直接引用 name，不要重复定义。除 aliases、conditions 外，字段都是字符串或布尔值，不要写成数组。本次没要求的数组必须是 []。
            每个字段必须写成 "键": 值。不能写成 "键", 值，也不能省略冒号。
            """;

    private final LlmClient llm;
    private final MetadataStore metadataStore;
    private final CatalogRegistry catalogRegistry;
    private final QueryExecutor queryExecutor;
    private final OntologyRepository repository;
    private final ObjectMapper objectMapper;
    private final int sampleRows;

    public OntologyDraftService(
            LlmClient llm,
            MetadataStore metadataStore,
            CatalogRegistry catalogRegistry,
            QueryExecutor queryExecutor,
            OntologyRepository repository,
            ObjectMapper objectMapper,
            FederationProperties properties) {
        this.llm = llm;
        this.metadataStore = metadataStore;
        this.catalogRegistry = catalogRegistry;
        this.queryExecutor = queryExecutor;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.sampleRows = properties.getAsk().getSampleRows();
    }

    public DraftResult draft(DraftRequest request, Consumer<DraftPhase> progress) {
        llm.model();
        if (request.subject() == null || request.subject().isBlank()) {
            throw new IllegalArgumentException("请先填写主题域，本次勾选的表都会归到这个主题域");
        }
        String subject = request.subject().trim();
        List<TableSnapshot> tables = resolveTables(request.tables());
        requireTablesInSubject(repository.all(), tables, subject);
        List<String> warnings = new ArrayList<>();
        Map<String, List<List<Object>>> samples = new LinkedHashMap<>();
        begin(progress, "sample", "读取列和样例");
        for (TableSnapshot table : tables) {
            samples.put(table.key(), sample(table, warnings));
        }
        finish(progress, "sample", "已读取 " + tables.size() + " 张表的列和样例", tables.size(), List.copyOf(warnings));
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (TableSnapshot table : tables) {
            columns.put(table.key().toLowerCase(Locale.ROOT), columnNames(table));
        }
        Snapshot ontology = repository.within(subject).snapshot();
        for (Concept concept : ontology.concepts()) {
            if (concept.table() == null || concept.table().isBlank()) {
                continue;
            }
            metadataStore.find(concept.table()).ifPresent(snapshot ->
                    columns.putIfAbsent(snapshot.key().toLowerCase(Locale.ROOT), columnNames(snapshot)));
            String key = concept.table().toLowerCase(Locale.ROOT);
            if (!columns.containsKey(key)) {
                List<String> mapped = ontology.attributes().stream()
                        .filter(attribute -> concept.name().equals(attribute.domain()) && attribute.column() != null)
                        .map(Attribute::column)
                        .distinct()
                        .toList();
                if (!mapped.isEmpty()) {
                    columns.put(key, mapped);
                    warnings.add("已有概念 " + concept.name() + " 的表 " + concept.table()
                            + " 不在元数据里，建关系时只能用它已有属性的列");
                }
            }
        }
        return runStages(repository, objectMapper, subject, tables, samples, columns, request.instructions(),
                (system, user) -> llm.complete(system, user),
                (table, column) -> lookupValues(tables, table, column, warnings),
                (table, codeColumn, labelColumn, commentColumn) ->
                        lookupDictionary(tables, table, codeColumn, labelColumn, commentColumn, warnings),
                progress, warnings);
    }

    /** 一张表只能属于一个主题域：别的主题域已经有这张表的概念时不能再起草。 */
    static void requireTablesInSubject(Snapshot all, List<TableSnapshot> tables, String subject) {
        for (TableSnapshot table : tables) {
            for (Concept concept : all.concepts()) {
                if (concept.table() == null || !concept.table().equalsIgnoreCase(table.key())) {
                    continue;
                }
                String owner = concept.subject() == null || concept.subject().isBlank() ? null : concept.subject().trim();
                if (!Objects.equals(owner, subject)) {
                    throw new IllegalArgumentException("表 " + table.key() + " 已经是" + (owner == null ? "未分主题域" : "主题域 " + owner)
                            + " 里的概念 " + concept.name() + "，一张表只能属于一个主题域；要换主题域请先删除这个概念");
                }
            }
        }
    }

    /** 概念、属性、码值、关系、派生概念分段起草。属性按列分批，码值和关系只根据读到的数据来写。 */
    static DraftResult runStages(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            String instructions,
            StageCaller caller,
            ValueLookup values,
            Consumer<DraftPhase> progress,
            List<String> warnings) {
        return runStages(repository, objectMapper, null, tables, samples, columns, instructions, caller, values, null,
                progress, warnings);
    }

    static DraftResult runStages(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            String instructions,
            StageCaller caller,
            ValueLookup values,
            DictionaryLookup dictionaries,
            Consumer<DraftPhase> progress,
            List<String> warnings) {
        return runStages(repository, objectMapper, null, tables, samples, columns, instructions, caller, values,
                dictionaries, progress, warnings);
    }

    /**
     * @param subject 主题域。本次起草只看得到这个主题域里已有的概念，关系、码值归并和派生概念都不会跨主题域；
     *                为 null 时不分主题域。
     */
    static DraftResult runStages(
            OntologyRepository whole,
            ObjectMapper objectMapper,
            String subject,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            String instructions,
            StageCaller caller,
            ValueLookup values,
            DictionaryLookup dictionaries,
            Consumer<DraftPhase> progress,
            List<String> warnings) {
        OntologyRepository repository = subject == null ? whole : whole.within(subject);
        if (subject != null) {
            requireTablesInSubject(whole.all(), tables, subject);
        }
        String note = instructions == null || instructions.isBlank() ? "" : "\n补充要求：" + instructions.trim() + "\n";
        String tablesText = describeTables(tables, samples);
        Map<String, Map<String, String>> physicalTypes = physicalTypes(tables);
        ValueLookup lookup = values == null ? (table, column) -> List.of() : values;

        begin(progress, "concepts", "起草概念");
        int mark = warnings.size();
        String conceptTask = "只起草概念：给出的每张表恰好一个类，table 必须等于表的全名。"
                + "name 写一个英文业务名即可，系统会改成由表名生成的名字，并把你写的名字留作别名。"
                + "labelZh 用这张表自己的业务名称，comment 用一句话说明它表示什么。"
                + "明细、日志、流水、记录这类大表 largeTable=true，timeColumn 填该表真实的时间列。parent 只引用已有本体里更泛的类。";
        String conceptShape = example(
                "{\"concepts\":[{\"name\":\"Order\",\"labelZh\":\"订单\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"一笔订单\",\"parent\":\"\",\"table\":\"catalog.schema.orders\",\"timeColumn\":\"createdAt\",\"largeTable\":false}]}");
        DraftResult concepts;
        try {
            concepts = applyStage(repository, objectMapper, columns, physicalTypes, null, "concepts",
                    stagePrompt(conceptTask, conceptShape),
                    describeExisting(repository.snapshot()) + tablesText + note,
                    caller, warnings);
        } catch (IllegalStateException first) {
            warnings.add("概念阶段输出不完整，改为只按列名重试");
            concepts = applyStage(repository, objectMapper, columns, physicalTypes, null, "concepts",
                    stagePrompt(conceptTask, conceptShape),
                    describeExisting(repository.snapshot()) + describeColumnNames(tables) + note,
                    caller, warnings);
        }
        finish(progress, "concepts", "概念已写入 " + concepts.concepts() + " 个", concepts.concepts(), since(warnings, mark));

        int attributes = 0;
        String attributeShape = example(
                "{\"attributes\":[{\"name\":\"orderStatus\",\"labelZh\":\"订单状态\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"\",\"domain\":\"Order\",\"datatype\":\"string\",\"column\":\"status\",\"unit\":\"\",\"identifier\":false,\"codeList\":\"OrderStatus\"}]}");
        for (TableSnapshot table : tables) {
            String concept = conceptForTable(repository.snapshot(), table.key());
            List<ColumnSnapshot> business = businessColumns(table.getColumns());
            if (concept.isBlank()) {
                warnings.add("跳过属性 " + table.key() + "：这张表没有写成概念");
                continue;
            }
            if (business.isEmpty()) {
                warnings.add("跳过属性 " + table.key() + "：没有可映射的列");
                continue;
            }
            List<List<ColumnSnapshot>> batches = columnBatches(business, ATTRIBUTE_BATCH);
            String siblings = business.stream().map(ColumnSnapshot::getName).collect(Collectors.joining("、"));
            for (int i = 0; i < batches.size(); i++) {
                String names = batches.get(i).stream().map(ColumnSnapshot::getName).collect(Collectors.joining("、"));
                begin(progress, "attributes", "起草属性 " + table.key() + "（" + (i + 1) + "/" + batches.size() + "）：" + names);
                mark = warnings.size();
                int written = writeAttributeBatch(repository, objectMapper, columns, physicalTypes, caller, warnings,
                        table, batches.get(i), samples.getOrDefault(table.key(), List.of()), concept, siblings, note,
                        attributeShape, true);
                attributes += written;
                finish(progress, "attributes", "这批已写入 " + written + " 个属性", written, since(warnings, mark));
            }
        }

        int codeCount = 0;
        int codeMark = warnings.size();
        warnings.addAll(attachSuggestedCodeLists(repository, tables, samples));
        Set<String> fromDimension = new LinkedHashSet<>();
        codeCount += loadDimensionCodes(repository, tables, samples, columns, physicalTypes, dictionaries, fromDimension, warnings);
        Map<String, Set<String>> allowedCodes = new LinkedHashMap<>();
        String evidence = describeCodeEvidence(repository.snapshot(), tables, samples, lookup, allowedCodes, fromDimension);
        String codeShape = example(
                "{\"codes\":[{\"name\":\"OrderStatus_1\",\"codeList\":\"OrderStatus\",\"value\":\"1\",\"labelZh\":\"启用\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"\"}]}");
        if (evidence == null) {
            String message = codeCount > 0 ? "从维表写入 " + codeCount + " 个码值" : "没有码值列，跳过";
            finish(progress, "codes", message, codeCount, since(warnings, codeMark));
        } else if (allowedCodes.isEmpty()) {
            begin(progress, "codes", "按列含义起草码值");
            mark = codeMark;
            DraftResult codes = applyStage(repository, objectMapper, columns, physicalTypes, null, "codes",
                    stagePrompt("没有读到具体取值。只根据这张表的表名、列名和补充说明推断。能确定该业务自己的编码就写，value 用编码，labelZh 用中文。"
                                    + "不能确定就返回空数组，不要套用别的行业的编码。每个集合最多 12 个。",
                            codeShape),
                    evidence + note,
                    caller, warnings);
            codeCount += codes.codes();
            finish(progress, "codes", "码值已写入 " + codeCount + " 个", codeCount, since(warnings, mark));
        } else {
            String lists = String.join("、", allowedCodes.keySet());
            begin(progress, "codes", "按读到的取值起草码值：" + lists);
            mark = codeMark;
            DraftResult codes = applyStage(repository, objectMapper, columns, physicalTypes, allowedCodes, "codes",
                    stagePrompt("取值列表里的每一个值都要写一条码值。labelZh 写成这张表业务里的中文含义，不要把中文名留成数字。"
                                    + "例如编码 1 写成启用，不要写成 1。含义要跟当前表一致，不要套用别的行业。列表以外的编码不要写。"
                                    + "codeList 照抄上面给出的码值集合名，name 由系统按集合名加取值生成，可以留空。",
                            codeShape),
                    evidence + note,
                    caller, warnings);
            codeCount += codes.codes();
            finish(progress, "codes", "码值已写入 " + codeCount + " 个", codeCount, since(warnings, mark));
        }

        long mappedConcepts = repository.snapshot().concepts().stream()
                .filter(concept -> concept.table() != null && !concept.table().isBlank())
                .count();
        Set<String> draftedTables = new LinkedHashSet<>();
        tables.forEach(table -> draftedTables.add(table.key().toLowerCase(Locale.ROOT)));
        List<Concept> mapped = repository.snapshot().concepts().stream()
                .filter(concept -> concept.table() != null && !concept.table().isBlank())
                .toList();
        List<String> added = mapped.stream()
                .filter(concept -> draftedTables.contains(concept.table().toLowerCase(Locale.ROOT)))
                .map(Concept::name)
                .toList();
        boolean incremental = !added.isEmpty() && added.size() < mapped.size();
        String joinHints = describeJoinHints(repository.snapshot(), columns, incremental ? draftedTables : null);
        String focus = incremental
                ? "\n本次新加的概念：" + String.join("、", added) + "。逐个判断它们和已有本体里每个概念能不能连上，"
                        + "再判断它们彼此之间的连接。已有关系不要重复写，也不要占用已有关系的名字。\n"
                : "";
        int relationCount = 0;
        Set<String> earlierRelations = new LinkedHashSet<>();
        repository.snapshot().relations().forEach(relation -> earlierRelations.add(relation.name()));
        Set<String> redraftDefined = new LinkedHashSet<>();
        if (mappedConcepts < 2) {
            finish(progress, "relations", "不足两个概念，跳过关系", 0, List.of());
        } else {
            begin(progress, "relations", "起草关系");
            mark = warnings.size();
            DraftResult relations = applyStage(repository, objectMapper, columns, physicalTypes, null, "relations",
                    stagePrompt("起草这些概念之间能用于问数的关系。优先写出已经对上的连接，也可以按主键、外键、同名业务键、编码列和字典表补充。"
                                    + "例如一张表的客户编号连接客户表的主键。joinFrom、joinTo 只写列名，例如 customerId，不要写 Order.customerId。"
                                    + "同一对概念可以有多条关系。确实没有连接才返回空数组。",
                            example("{\"relations\":[{\"name\":\"placedBy\",\"labelZh\":\"下单客户\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"\",\"domain\":\"Order\",\"range\":\"Customer\",\"joinFrom\":\"customerId\",\"joinTo\":\"id\",\"inverse\":\"\",\"transitive\":false,\"functional\":false}]}")),
                    describeExisting(repository.snapshot()) + describeRelations(repository.snapshot())
                            + describeConceptColumns(repository.snapshot(), columns) + joinHints + focus + note,
                    caller, warnings);
            relationCount = relations.relations();
            finish(progress, "relations", "关系已写入 " + relationCount + " 个", relationCount, since(warnings, mark));

            begin(progress, "relations", "按关系归并码值");
            mark = warnings.size();
            Set<String> merged = mergeCodesByRelation(repository, tables, samples, columns, physicalTypes, dictionaries, warnings);
            Snapshot afterMerge = repository.snapshot();
            for (String concept : merged) {
                if (!isDrafted(tables, conceptTable(afterMerge, concept))) {
                    redraftDefined.add(concept);
                }
            }
            for (Relation relation : afterMerge.relations()) {
                if (earlierRelations.contains(relation.name())) {
                    continue;
                }
                for (String concept : List.of(Objects.toString(relation.domain(), ""), Objects.toString(relation.range(), ""))) {
                    if (!concept.isEmpty() && !isDrafted(tables, conceptTable(afterMerge, concept))
                            && !describeJoinedCodes(afterMerge, concept).isEmpty()) {
                        redraftDefined.add(concept);
                    }
                }
            }
            finish(progress, "relations", merged.isEmpty() ? "没有需要归并的码值" : "已把 " + merged.size() + " 个概念的码值归到字典一端",
                    merged.size(), since(warnings, mark));
        }

        int definedCount = 0;
        if (repository.snapshot().attributes().isEmpty()) {
            finish(progress, "defined", "没有属性，跳过派生概念", 0, List.of());
        } else {
            String definedPrompt = stagePrompt("只起草派生概念。要写成能直接用于问数的子集，优先用已有码值做等值或比较，例如状态等于已关闭、数量大于等于某个已出现的阈值。"
                            + "attribute 写属性名，即括号前的英文名（例如 wlsDay01_LogonType）；"
                            + "value 写码值的取值（等号左边），不写中文名，也可以来自样例或补充说明；operator 只能用 =、>、>=、<、<=。"
                            + "业务上常用的子集都要写，最多 " + DEFINED_PER_CONCEPT + " 个，每个最多 2 个条件；已有的派生概念不要重复。"
                            + "base 只能是本次指定的概念。这个概念没有能区分子集的属性（例如只是字典表）就返回空数组。",
                    example("{\"definedConcepts\":[{\"name\":\"ClosedOrder\",\"labelZh\":\"已关闭订单\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"\",\"base\":\"Order\",\"conditions\":[{\"attribute\":\"order_status\",\"operator\":\"=\",\"value\":\"closed\"}],\"definitionText\":\"\"}]}"));
            List<Concept> targets = new ArrayList<>();
            for (TableSnapshot table : tables) {
                repository.snapshot().concepts().stream()
                        .filter(item -> item.table() != null && item.table().equalsIgnoreCase(table.key()))
                        .findFirst().ifPresent(targets::add);
            }
            for (Concept concept : repository.snapshot().concepts()) {
                if (redraftDefined.contains(concept.name()) && !targets.contains(concept)) {
                    targets.add(concept);
                }
            }
            for (Concept concept : targets) {
                boolean earlier = redraftDefined.contains(concept.name());
                begin(progress, "defined", (earlier ? "为已有概念补起草派生概念 " : "起草派生概念 ") + concept.name());
                mark = warnings.size();
                DraftResult defined = applyStage(repository, objectMapper, columns, physicalTypes, null, "defined",
                        definedPrompt,
                        describeExisting(repository.snapshot()) + describeJoinedCodes(repository.snapshot(), concept.name())
                                + describeDefined(repository.snapshot(), concept.name())
                                + "\n本次只为概念 " + concept.name()
                                + "（表 " + concept.table() + "）起草派生概念，base 填 " + concept.name() + "。"
                                + (earlier ? "它是已有概念，这次新连上了别的概念，只补充用得上新码值的派生概念。" : "") + note,
                        caller, warnings);
                definedCount += defined.definedConcepts();
                finish(progress, "defined", concept.name() + " 的派生概念已写入 " + defined.definedConcepts() + " 个",
                        defined.definedConcepts(), since(warnings, mark));
            }
        }

        return new DraftResult(concepts.concepts(), attributes, relationCount, codeCount, definedCount, warnings);
    }

    private static void begin(Consumer<DraftPhase> progress, String phase, String message) {
        progress.accept(new DraftPhase(phase, message, "start", 0, List.of()));
    }

    private static void finish(Consumer<DraftPhase> progress, String phase, String message, int saved, List<String> notes) {
        progress.accept(new DraftPhase(phase, message, "done", saved, notes));
    }

    private static List<String> since(List<String> warnings, int mark) {
        return List.copyOf(warnings.subList(mark, warnings.size()));
    }

    private List<String> lookupValues(List<TableSnapshot> tables, String tableKey, String column, List<String> warnings) {
        TableSnapshot table = tables.stream().filter(item -> item.key().equalsIgnoreCase(tableKey)).findFirst().orElse(null);
        if (table == null || column == null || column.isBlank()) {
            return List.of();
        }
        String sql = "SELECT " + quote(column) + " FROM " + quote(table.getCatalog()) + "." + quote(table.getSchema())
                + "." + quote(table.getTable()) + " GROUP BY 1 ORDER BY count(*) DESC LIMIT 20";
        try {
            QueryResponse.ResultSetData data = queryExecutor.execute(sql, 20, 30);
            List<String> values = new ArrayList<>();
            for (List<Object> row : data.rows()) {
                if (row.isEmpty() || row.get(0) == null) {
                    continue;
                }
                String text = String.valueOf(row.get(0)).trim();
                if (!text.isEmpty() && text.length() <= 40 && !values.contains(text)) {
                    values.add(text);
                }
            }
            return values;
        } catch (RuntimeException e) {
            warnings.add("读取码值取值失败 " + tableKey + "." + column + "：" + e.getMessage());
            return List.of();
        }
    }

    private List<List<String>> lookupDictionary(
            List<TableSnapshot> tables,
            String tableKey,
            String codeColumn,
            String labelColumn,
            String commentColumn,
            List<String> warnings) {
        TableSnapshot table = tables.stream().filter(item -> item.key().equalsIgnoreCase(tableKey)).findFirst().orElse(null);
        if (table == null) {
            warnings.add("维表不在本次勾选中，无法读取 " + tableKey + "." + labelColumn);
            return List.of();
        }
        String select = quote(codeColumn) + ", max(" + quote(labelColumn) + ")";
        if (commentColumn != null && !commentColumn.isBlank()) {
            select += ", max(" + quote(commentColumn) + ")";
        }
        String sql = "SELECT " + select + " FROM " + quote(table.getCatalog()) + "." + quote(table.getSchema())
                + "." + quote(table.getTable()) + " GROUP BY 1 LIMIT " + DICTIONARY_LIMIT;
        try {
            QueryResponse.ResultSetData data = queryExecutor.execute(sql, DICTIONARY_LIMIT, 30);
            List<List<String>> rows = new ArrayList<>();
            for (List<Object> row : data.rows()) {
                if (row.isEmpty() || row.get(0) == null) {
                    continue;
                }
                String code = String.valueOf(row.get(0)).trim();
                String label = row.size() > 1 && row.get(1) != null ? String.valueOf(row.get(1)).trim() : "";
                String comment = row.size() > 2 && row.get(2) != null ? String.valueOf(row.get(2)).trim() : "";
                if (!code.isEmpty() && !label.isEmpty()) {
                    rows.add(commentColumn == null ? List.of(code, label) : List.of(code, label, comment));
                }
            }
            if (rows.size() >= DICTIONARY_LIMIT) {
                warnings.add("维表 " + tableKey + " 的码值超过 " + DICTIONARY_LIMIT + " 条，只写入前 " + DICTIONARY_LIMIT + " 条");
            }
            return rows;
        } catch (RuntimeException e) {
            warnings.add("读取维表 " + tableKey + "." + labelColumn + " 失败：" + e.getMessage());
            return List.of();
        }
    }

    private static String describeColumnNames(List<TableSnapshot> tables) {
        StringBuilder text = new StringBuilder();
        for (TableSnapshot table : tables) {
            List<ColumnSnapshot> columns = table.getColumns() == null ? List.of() : table.getColumns();
            text.append("\n表 ").append(table.key()).append(" 的列：");
            text.append(columns.stream().map(ColumnSnapshot::getName).collect(Collectors.joining("、")));
            text.append('\n');
        }
        return text.toString();
    }

    private static DraftResult applyStage(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            Map<String, Set<String>> allowedCodes,
            String section,
            String system,
            String user,
            StageCaller caller,
            List<String> warnings) {
        try {
            return applyParsed(repository, objectMapper, columns, physicalTypes, allowedCodes, section, system, user, caller, warnings);
        } catch (IllegalStateException e) {
            if ("concepts".equals(section)) {
                throw e;
            }
            try {
                String retry = system + "\n上一次输出无法解析。只输出一个更短的完整 JSON 对象，每个字段写成 \"键\": 值，数组和对象都要闭合，不要省略冒号。\n";
                return applyParsed(repository, objectMapper, columns, physicalTypes, allowedCodes, section, retry, user, caller, warnings);
            } catch (IllegalStateException again) {
                warnings.add(sectionName(section) + "阶段未完成：" + again.getMessage());
                return new DraftResult(0, 0, 0, 0, 0, List.of());
            }
        }
    }

    private static DraftResult applyParsed(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            Map<String, Set<String>> allowedCodes,
            String section,
            String system,
            String user,
            StageCaller caller,
            List<String> warnings) {
        Snapshot parsed = parseDraft(objectMapper, caller.complete(system, user));
        DraftResult result = apply(repository, slice(parsed, section), repository.snapshot(), columns, physicalTypes, allowedCodes);
        warnings.addAll(result.warnings());
        return result;
    }

    private static Snapshot slice(Snapshot draft, String section) {
        return switch (section) {
            case "concepts" -> new Snapshot(draft.concepts(), List.of(), List.of(), List.of(), List.of());
            case "attributes" -> new Snapshot(List.of(), draft.attributes(), List.of(), List.of(), List.of());
            case "relations" -> new Snapshot(List.of(), List.of(), draft.relations(), List.of(), List.of());
            case "codes" -> new Snapshot(List.of(), List.of(), List.of(), draft.codes(), List.of());
            case "defined" -> new Snapshot(List.of(), List.of(), List.of(), List.of(), draft.definedConcepts());
            default -> throw new IllegalArgumentException(section);
        };
    }

    private static String sectionName(String section) {
        return switch (section) {
            case "attributes" -> "属性";
            case "relations" -> "关系";
            case "codes" -> "码值";
            case "defined" -> "派生概念";
            default -> section;
        };
    }

    private static String stagePrompt(String task, String shape) {
        return STAGE_RULES + "\n本次只做一件事：" + task + "\n输出：\n" + shape + "\n";
    }

    private static String example(String json) {
        return json + "\n示例只说明格式，不要照抄示例里的订单或客户。";
    }

    private static int writeAttributeBatch(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            StageCaller caller,
            List<String> warnings,
            TableSnapshot table,
            List<ColumnSnapshot> batch,
            List<List<Object>> samples,
            String concept,
            String siblings,
            String note,
            String attributeShape,
            boolean splitWhenBroken) {
        int mark = warnings.size();
        DraftResult piece = applyStage(repository, objectMapper, columns, physicalTypes, null, "attributes",
                stagePrompt("只为下面这些列起草属性，每一列都要输出一个属性。column 必须是给出的列名，domain 必须是 " + concept + "。"
                                + "labelZh 写成这张表业务里的叫法，不要照抄列名。整数、时间列按类型填写 datatype。"
                                + "类型、状态、级别、结果，以及样例里反复出现的短编码，codeList 填任意非空名字标成码值列（系统会按表名和列名重新命名），不要在这里展开码值。"
                                + "主键、流水号和互不相同的 Id 填 identifier=true，不要当码值。",
                        attributeShape),
                "这张表的概念是 " + concept + "，domain 必须填 " + concept + "。\n本表业务列：" + siblings + "\n"
                        + describeColumns(table, batch, samples)
                        + note,
                caller, warnings);
        if (piece.attributes() > 0 || !splitWhenBroken || batch.size() <= 1 || !parseFailed(warnings, mark)) {
            return piece.attributes();
        }
        int mid = batch.size() / 2;
        warnings.add("这批 JSON 无法解析，拆成 " + mid + " 列和 " + (batch.size() - mid) + " 列重试");
        return writeAttributeBatch(repository, objectMapper, columns, physicalTypes, caller, warnings, table,
                batch.subList(0, mid), samples, concept, siblings, note, attributeShape, false)
                + writeAttributeBatch(repository, objectMapper, columns, physicalTypes, caller, warnings, table,
                batch.subList(mid, batch.size()), samples, concept, siblings, note, attributeShape, false);
    }

    private static boolean parseFailed(List<String> warnings, int mark) {
        return warnings.subList(mark, warnings.size()).stream().anyMatch(warning -> warning.contains("无法解析")
                || warning.contains("没写完") || warning.contains("没有返回"));
    }

    static List<ColumnSnapshot> businessColumns(List<ColumnSnapshot> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<ColumnSnapshot> business = columns.stream().filter(column -> !technicalColumn(column.getName())).toList();
        return business.isEmpty() ? List.copyOf(columns) : business;
    }

    static boolean technicalColumn(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.startsWith("standby") || normalized.startsWith("ext") || normalized.startsWith("_partition")
                || normalized.equals("version") || normalized.equals("sign");
    }

    static boolean codeLike(String name, List<String> samples) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("_", "");
        List<String> values = samples == null ? List.of() : samples.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (normalized.endsWith("name") || normalized.endsWith("host") || normalized.endsWith("path")
                || normalized.endsWith("description") || normalized.endsWith("reason") || normalized.endsWith("message")
                || normalized.endsWith("user") || normalized.endsWith("comment")) {
            return false;
        }
        if (values.stream().anyMatch(value -> value.length() > 24)) {
            return false;
        }
        if (normalized.endsWith("type") || normalized.endsWith("status") || normalized.endsWith("level")
                || normalized.endsWith("code") || normalized.endsWith("kind") || normalized.endsWith("category")
                || normalized.endsWith("state") || normalized.endsWith("flag") || normalized.endsWith("result")) {
            return true;
        }
        return enumerated(values);
    }

    private static boolean enumerated(List<String> values) {
        if (values.isEmpty() || values.stream().anyMatch(value -> value.length() > 6)) {
            return false;
        }
        long distinct = values.stream().distinct().count();
        if (distinct < values.size()) {
            return true;
        }
        return values.stream().allMatch(value -> value.matches("\\d{1,4}"));
    }

    static String conceptForTable(Snapshot snapshot, String tableKey) {
        return snapshot.concepts().stream()
                .filter(concept -> concept.table() != null && concept.table().equalsIgnoreCase(tableKey))
                .map(Concept::name)
                .findFirst()
                .orElse("");
    }

    static List<String> attachSuggestedCodeLists(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples) {
        List<String> notes = new ArrayList<>();
        for (Attribute attribute : List.copyOf(repository.snapshot().attributes())) {
            if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
                continue;
            }
            String table = conceptTable(repository.snapshot(), attribute.domain());
            List<String> seen = sampleValues(tables, samples, table, attribute.column());
            if (attribute.domain() == null || attribute.column() == null || table == null || table.isBlank()
                    || !codeLike(attribute.column(), seen)) {
                continue;
            }
            if (seen.stream().anyMatch(value -> value.length() > 24)) {
                continue;
            }
            String codeList = OntologyRepository.codeListName(table, attribute.column());
            if (save(notes, "属性 " + attribute.name(), () -> repository.saveAttribute(withCodeList(attribute, codeList))) > 0) {
                notes.add(attribute.column() + " 标成码值 " + codeList);
            }
        }
        return notes;
    }

    private static final int DICTIONARY_LIMIT = 2000;
    private static final int NARROW_TABLE = 8;

    /**
     * 维表由结构决定，不看表名、概念名或列名。
     * 关系的一端列少，并且除连接列外还有更长的文本列；或者一张窄表里有被其他表共用的短编码列，旁边是文本列。
     * 较短的文本列当码值名称，较长的文本列当说明。
     */
    static List<Dimension> findDimensions(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples) {
        Map<String, List<List<Object>>> rows = samples == null ? Map.of() : samples;
        Map<String, Dimension> found = new LinkedHashMap<>();
        for (Relation relation : list(snapshot.relations())) {
            addDimensionSide(snapshot, tables, columns, rows, relation.range(), relation.joinTo(), found);
            addDimensionSide(snapshot, tables, columns, rows, relation.domain(), relation.joinFrom(), found);
        }
        for (TableSnapshot table : tables) {
            List<ColumnSnapshot> business = businessColumns(table.getColumns());
            if (business.size() > NARROW_TABLE) {
                continue;
            }
            String code = codeColumn(table, business, snapshot, columns, rows);
            TextPair text = textColumns(table, business, code, rows);
            if (code == null || text.label() == null) {
                continue;
            }
            String concept = conceptForTable(snapshot, table.key());
            if (concept.isBlank()) {
                continue;
            }
            found.putIfAbsent(dimensionKey(concept, code), new Dimension(concept, table.key(), code, text.label(), text.comment()));
        }
        return List.copyOf(found.values());
    }

    private static int loadDimensionCodes(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            DictionaryLookup dictionaries,
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

    private static boolean isDrafted(List<TableSnapshot> tables, String tableKey) {
        return tableKey != null && tables.stream().anyMatch(table -> table.key().equalsIgnoreCase(tableKey));
    }

    /** 从一张维表读出编码和名称，写成它编码列上的码值。 */
    private static int loadDimension(
            OntologyRepository repository,
            Dimension dimension,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            DictionaryLookup dictionaries,
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
            codes.add(new Code(codeList + "_" + identifierSuffix(value), codeList, value, label, null, aliases, comment));
        }
        if (codes.isEmpty()) {
            return 0;
        }
        DraftResult applied = apply(repository,
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
    static Set<String> mergeCodesByRelation(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            DictionaryLookup dictionaries,
            List<String> warnings) {
        Map<String, List<List<Object>>> rows = samples == null ? Map.of() : samples;
        Set<String> affected = new LinkedHashSet<>();
        for (Relation relation : List.copyOf(repository.snapshot().relations())) {
            Snapshot snapshot = repository.snapshot();
            if (relation.domain() == null || relation.range() == null
                    || (!isDrafted(tables, conceptTable(snapshot, relation.domain()))
                    && !isDrafted(tables, conceptTable(snapshot, relation.range())))) {
                continue;
            }
            Attribute from = attributeOnColumn(snapshot, relation.domain(), relation.joinFrom());
            Attribute to = attributeOnColumn(snapshot, relation.range(), relation.joinTo());
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
            for (Code code : list(snapshot.codes())) {
                if (owner.codeList().equals(code.codeList()) && code.value() != null) {
                    known.add(code.value().trim());
                }
            }
            List<String> own = list(snapshot.codes()).stream()
                    .filter(code -> fact.codeList().equals(code.codeList()) && code.value() != null)
                    .map(code -> code.value().trim())
                    .toList();
            List<String> missing = own.stream().filter(value -> !known.contains(value)).toList();
            if (save(warnings, "属性 " + fact.name(), () -> repository.saveAttribute(withCodeList(fact, null))) == 0) {
                continue;
            }
            affected.add(fact.domain());
            warnings.add(fact.domain() + "." + fact.column() + " 通过关系 " + relation.name() + " 连到字典概念 " + owner.domain()
                    + "，码值只留在 " + owner.name() + " 上，已删除它自己的 " + own.size() + " 个码值"
                    + (missing.isEmpty() ? "" : "；其中字典里没有的取值：" + String.join("、", missing)));
        }
        return affected;
    }

    private static Dimension dimensionSide(
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

    private static Attribute attributeNamed(Snapshot snapshot, String name) {
        return list(snapshot.attributes()).stream().filter(attribute -> attribute.name().equals(name)).findFirst().orElse(null);
    }

    private static boolean hasCodes(Snapshot snapshot, Attribute attribute) {
        return attribute != null && attribute.codeList() != null && !attribute.codeList().isBlank()
                && list(snapshot.codes()).stream().anyMatch(code -> attribute.codeList().equals(code.codeList()));
    }

    /**
     * 维表码值只挂在维表概念自己的编码列上。事实表里同名的编码列不再另建码值，
     * 问数时命中码值就锁定维表概念，再通过关系连到事实表。
     */
    private static String bindCodeList(
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
        String table = conceptTable(snapshot, dimension.concept());
        String codeList = OntologyRepository.codeListName(table == null || table.isBlank() ? dimension.tableKey() : table,
                own.column());
        if (!codeList.equals(own.codeList())) {
            Attribute target = own;
            if (save(warnings, "属性 " + own.name(), () -> repository.saveAttribute(withCodeList(target, codeList))) == 0) {
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
            String otherTable = conceptTable(snapshot, attribute.domain());
            if (otherTable == null || !drafted.contains(otherTable.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (save(warnings, "属性 " + attribute.name(), () -> repository.saveAttribute(withCodeList(attribute, null))) > 0) {
                warnings.add(attribute.domain() + "." + attribute.column() + " 的含义来自维表 " + dimension.tableKey()
                        + "（概念 " + dimension.concept() + "），不再单独建码值，问数时通过关系连到维表");
            }
        }
        return codeList;
    }

    private static Attribute withCodeList(Attribute attribute, String codeList) {
        return new Attribute(attribute.name(), attribute.labelZh(), attribute.labelEn(), attribute.aliases(),
                attribute.comment(), attribute.domain(), attribute.datatype(), attribute.column(),
                attribute.unit(), attribute.identifier(), codeList);
    }

    private static void addDimensionSide(
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
        String tableKey = conceptTable(snapshot, concept);
        if (tableKey == null) {
            return;
        }
        List<String> names = columns.getOrDefault(tableKey.toLowerCase(Locale.ROOT), List.of());
        if (names.isEmpty() || names.size() > NARROW_TABLE || names.stream().noneMatch(name -> name.equalsIgnoreCase(joinColumn))) {
            return;
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Attribute attribute : list(snapshot.attributes())) {
            if (concept.equals(attribute.domain()) && attribute.column() != null && attribute.datatype() != null) {
                types.put(attribute.column().toLowerCase(Locale.ROOT), attribute.datatype());
            }
        }
        TableSnapshot table = tableOrNames(tables, tableKey, names, types);
        List<ColumnSnapshot> business = businessColumns(table.getColumns());
        String code = business.stream().map(ColumnSnapshot::getName).filter(name -> name.equalsIgnoreCase(joinColumn)).findFirst().orElse(joinColumn);
        TextPair text = textColumns(table, business, code, samples);
        if (text.label() == null) {
            return;
        }
        found.putIfAbsent(dimensionKey(concept, code), new Dimension(concept, tableKey, code, text.label(), text.comment()));
    }

    private static String codeColumn(
            TableSnapshot table,
            List<ColumnSnapshot> business,
            Snapshot snapshot,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples) {
        String shared = null;
        String fallback = null;
        for (ColumnSnapshot column : business) {
            List<String> seen = sampleValues(List.of(table), samples, table.key(), column.getName());
            if (isText(column, seen)) {
                continue;
            }
            if (sharedWithOther(column.getName(), table.key(), snapshot, columns)) {
                shared = column.getName();
            } else if (fallback == null && (numericType(column) || codeLike(column.getName(), seen) || !seen.isEmpty())) {
                fallback = column.getName();
            }
        }
        return shared != null ? shared : fallback;
    }

    private static TextPair textColumns(
            TableSnapshot table,
            List<ColumnSnapshot> business,
            String code,
            Map<String, List<List<Object>>> samples) {
        if (code == null) {
            return new TextPair(null, null);
        }
        List<ColumnSnapshot> texts = new ArrayList<>();
        for (ColumnSnapshot column : business) {
            if (column.getName().equalsIgnoreCase(code) || technicalColumn(column.getName())) {
                continue;
            }
            List<String> seen = sampleValues(List.of(table), samples, table.key(), column.getName());
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

    private static int averageLength(TableSnapshot table, String column, Map<String, List<List<Object>>> samples) {
        List<String> seen = sampleValues(List.of(table), samples, table.key(), column);
        if (seen.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return seen.stream().mapToInt(String::length).sum() / seen.size();
    }

    private static boolean isText(ColumnSnapshot column, List<String> samples) {
        if (!samples.isEmpty()) {
            boolean prose = samples.stream().anyMatch(OntologyDraftService::prose);
            boolean code = samples.stream().allMatch(OntologyDraftService::shortToken);
            return prose && !code;
        }
        String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
        return type.contains("char") || type.contains("string") || type.contains("text");
    }

    private static boolean prose(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 8) {
            return true;
        }
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static boolean shortToken(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 8 && text.matches("[A-Za-z0-9_\\-]+");
    }

    private static boolean numericType(ColumnSnapshot column) {
        String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
        return type.contains("int") || type.contains("number") || type.contains("decimal")
                || type.contains("numeric") || type.contains("double") || type.contains("float");
    }

    private static boolean sharedWithOther(String column, String tableKey, Snapshot snapshot, Map<String, List<String>> columns) {
        for (Concept concept : list(snapshot.concepts())) {
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
    private static TableSnapshot tableOrNames(List<TableSnapshot> tables, String tableKey, List<String> names,
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

    private static String dimensionKey(String concept, String column) {
        return concept + "|" + column.toLowerCase(Locale.ROOT);
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    record Dimension(String concept, String tableKey, String codeColumn, String labelColumn, String commentColumn) {
    }

    private record TextPair(String label, String comment) {
    }

    static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            ValueLookup lookup,
            Map<String, Set<String>> allowedCodes) {
        return describeCodeEvidence(snapshot, tables, samples, lookup, allowedCodes, Set.of());
    }

    static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            ValueLookup lookup,
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

    static String describeRelations(Snapshot snapshot) {
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

    static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns) {
        return describeJoinHints(snapshot, columns, null);
    }

    /** @param draftedTables 后加表时只提示至少一端是本次新表的连接；为 null 时提示全部。 */
    static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns, Set<String> draftedTables) {
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

    static String describeConceptColumns(Snapshot snapshot, Map<String, List<String>> columns) {
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

    private static List<String> relationColumns(List<String> names) {
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

    static String normalizeDatatype(String modelType, String physical) {
        String fromPhysical = datatypeOf(physical);
        if (modelType == null || modelType.isBlank() || !OntologyRepository.DATATYPES.contains(modelType)) {
            return fromPhysical == null ? "string" : fromPhysical;
        }
        if (fromPhysical == null) {
            return modelType;
        }
        if ("string".equals(modelType) && ("integer".equals(fromPhysical) || "long".equals(fromPhysical)
                || "decimal".equals(fromPhysical) || "double".equals(fromPhysical) || "boolean".equals(fromPhysical)
                || "date".equals(fromPhysical) || "dateTime".equals(fromPhysical))) {
            return fromPhysical;
        }
        if (("integer".equals(modelType) || "long".equals(modelType))
                && ("date".equals(fromPhysical) || "dateTime".equals(fromPhysical) || "boolean".equals(fromPhysical))) {
            return fromPhysical;
        }
        return modelType;
    }

    private static Map<String, Map<String, String>> physicalTypes(List<TableSnapshot> tables) {
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (TableSnapshot table : tables) {
            Map<String, String> columns = new LinkedHashMap<>();
            for (ColumnSnapshot column : list(table.getColumns())) {
                columns.put(column.getName().toLowerCase(Locale.ROOT), column.getType());
            }
            types.put(table.key().toLowerCase(Locale.ROOT), columns);
        }
        return types;
    }

    private static String physicalType(Map<String, Map<String, String>> types, String table, String column) {
        if (types == null || table == null || column == null) {
            return null;
        }
        Map<String, String> columns = types.get(table.toLowerCase(Locale.ROOT));
        return columns == null ? null : columns.get(column.toLowerCase(Locale.ROOT));
    }

    private static String datatypeOf(String physical) {
        if (physical == null || physical.isBlank()) {
            return null;
        }
        String type = physical.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("datetime") || type.contains("timestamp")) {
            return "dateTime";
        }
        if (type.equals("date")) {
            return "date";
        }
        if (type.contains("decimal") || type.contains("numeric")) {
            return "decimal";
        }
        if (type.contains("float") || type.contains("double")) {
            return "double";
        }
        if (type.contains("uint64") || type.contains("int64") || type.contains("bigint")) {
            return "long";
        }
        if (type.contains("int") || type.equals("long")) {
            return "integer";
        }
        return null;
    }

    private static String conceptTable(Snapshot snapshot, String concept) {
        return snapshot.concepts().stream()
                .filter(item -> item.name().equals(concept))
                .map(Concept::table)
                .findFirst()
                .orElse(null);
    }

    private static List<String> sampleValues(
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

    private static boolean sameJoinColumn(String left, String right) {
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

    private static void addForeignKey(
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

    private static void addJoin(Set<String> seen, List<String> lines, Concept left, String leftColumn, Concept right, String rightColumn) {
        String key = left.name() + "." + leftColumn + "=" + right.name() + "." + rightColumn;
        if (seen.add(key)) {
            lines.add("domain=" + left.name() + "，range=" + right.name()
                    + "，joinFrom=" + leftColumn + "，joinTo=" + rightColumn);
        }
    }

    private static String idStem(String column) {
        String compact = column.replace("_", "");
        if (compact.length() <= 2 || !compact.toLowerCase(Locale.ROOT).endsWith("id")) {
            return "";
        }
        return compact.substring(0, compact.length() - 2);
    }

    private static String tableLeaf(String table) {
        if (table == null || table.isBlank()) {
            return "";
        }
        int dot = table.lastIndexOf('.');
        return (dot < 0 ? table : table.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }

    static List<List<ColumnSnapshot>> columnBatches(List<ColumnSnapshot> columns, int size) {
        List<List<ColumnSnapshot>> batches = new ArrayList<>();
        for (int i = 0; i < columns.size(); i += size) {
            batches.add(List.copyOf(columns.subList(i, Math.min(i + size, columns.size()))));
        }
        return batches;
    }

    private static String describeTables(List<TableSnapshot> tables, Map<String, List<List<Object>>> samples) {
        StringBuilder text = new StringBuilder();
        for (TableSnapshot table : tables) {
            text.append(describeTable(table, samples.getOrDefault(table.key(), List.of())));
        }
        return text.toString();
    }

    static Snapshot parseDraft(ObjectMapper objectMapper, String reply) {
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
    static String repairJson(String json) {
        return MISSING_COLON.matcher(json).replaceAll("$1:");
    }

    static String closeTruncated(String json) {
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
    static void coerceDraft(JsonNode node) {
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

    private List<TableSnapshot> resolveTables(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("请至少勾选一张表");
        }
        List<TableSnapshot> tables = new ArrayList<>();
        for (String name : names) {
            TableSnapshot table = metadataStore.find(name)
                    .orElseThrow(() -> new IllegalArgumentException("元数据中没有这张表，请先采集: " + name));
            CatalogRecord catalog = catalogRegistry.get(table.getCatalog());
            if (catalog != null && "kafka".equalsIgnoreCase(catalog.getType())) {
                throw new IllegalArgumentException("Kafka 数据源暂不支持起草本体: " + name);
            }
            tables.add(table);
        }
        return tables;
    }

    private List<List<Object>> sample(TableSnapshot table, List<String> warnings) {
        String sql = "SELECT * FROM " + quote(table.getCatalog()) + "." + quote(table.getSchema()) + "."
                + quote(table.getTable()) + " LIMIT " + sampleRows;
        try {
            QueryResponse.ResultSetData data = queryExecutor.execute(sql, sampleRows);
            return data.rows();
        } catch (RuntimeException e) {
            warnings.add("抽样失败，仅按表结构起草 " + table.key() + "：" + e.getMessage());
            return List.of();
        }
    }

    static String describeExisting(Snapshot existing) {
        if (existing.concepts().isEmpty()) {
            return "已有本体：无\n";
        }
        StringBuilder text = new StringBuilder("已有本体（可直接引用这些类建立关系）：\n");
        Map<String, List<String>> attributesByDomain = existing.attributes().stream()
                .filter(attribute -> attribute.domain() != null)
                .collect(Collectors.groupingBy(Attribute::domain, LinkedHashMap::new,
                        Collectors.mapping(OntologyDraftService::describeAttribute, Collectors.toList())));
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
    static String describeJoinedCodes(Snapshot snapshot, String concept) {
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

    static String describeDefined(Snapshot snapshot, String concept) {
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

    private static String joinedCodeList(Snapshot snapshot, Attribute attribute) {
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

    private static Attribute attributeOnColumn(Snapshot snapshot, String concept, String column) {
        if (column == null) {
            return null;
        }
        return list(snapshot.attributes()).stream()
                .filter(attribute -> concept.equals(attribute.domain()) && column.equalsIgnoreCase(attribute.column()))
                .findFirst()
                .orElse(null);
    }

    private static String describeAttribute(Attribute attribute) {
        StringBuilder text = new StringBuilder(attribute.name()).append("（列 ").append(attribute.column());
        if (attribute.datatype() != null && !attribute.datatype().isBlank()) {
            text.append("，").append(attribute.datatype());
        }
        if (attribute.codeList() != null && !attribute.codeList().isBlank()) {
            text.append("，有码值");
        }
        return text.append('）').toString();
    }

    static String describeTable(TableSnapshot table, List<List<Object>> samples) {
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

    static String describeColumns(TableSnapshot table, List<ColumnSnapshot> columns, List<List<Object>> samples) {
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

    private static int indexOf(List<ColumnSnapshot> columns, ColumnSnapshot target) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(target.getName())) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(Object value) {
        if (value == null) {
            return "NULL";
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('|', '/');
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) + "…" : text;
    }

    /**
     * 校验草稿的物理映射后写入本体。列名按元数据纠正大小写；对不上的条目跳过并记入 warnings。
     */
    static DraftResult apply(
            OntologyRepository repository,
            Snapshot draft,
            Snapshot existing,
            Map<String, List<String>> tableColumns) {
        return apply(repository, draft, existing, tableColumns, Map.of(), null);
    }

    static DraftResult apply(
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
            String datatype = normalizeDatatype(attribute.datatype(), physicalType(physicalTypes, table, column));
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
    private static String outsideSubject(Map<String, String> inSubject, Map<String, String> everywhere, String... concepts) {
        for (String concept : concepts) {
            if (concept != null && !inSubject.containsKey(concept) && everywhere.containsKey(concept)) {
                return concept;
            }
        }
        return null;
    }

    /** 两端概念和连接列都相同就是同一条连接，方向反过来也算。 */
    private static boolean sameJoin(Relation other, String domain, String range, String from, String to) {
        if (other.joinFrom() == null || other.joinTo() == null) {
            return false;
        }
        boolean forward = Objects.equals(other.domain(), domain) && Objects.equals(other.range(), range)
                && other.joinFrom().equalsIgnoreCase(from) && other.joinTo().equalsIgnoreCase(to);
        boolean backward = Objects.equals(other.domain(), range) && Objects.equals(other.range(), domain)
                && other.joinFrom().equalsIgnoreCase(to) && other.joinTo().equalsIgnoreCase(from);
        return forward || backward;
    }

    private static String lowerFirst(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 模型常把条件写成列名（LogonType）或码值中文名（登录失败）。
     * 按基础概念的属性把列名换成属性名，把中文名换成码值取值。
     */
    static Condition fixCondition(String definedName, String base, Condition condition, Snapshot snapshot,
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
                ? target.codeList() : joinedCodeList(snapshot, target);
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

    private static String conceptOfTable(Map<String, String> conceptTables, String table) {
        return conceptTables.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().equalsIgnoreCase(table.trim()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** 概念名由表名生成，模型仍可能用它自己起的名字引用概念，按本批改名和概念别名找回。 */
    private static String resolveConcept(
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

    private static Attribute withDomain(Attribute attribute, String domain) {
        return new Attribute(attribute.name(), attribute.labelZh(), attribute.labelEn(), attribute.aliases(),
                attribute.comment(), domain, attribute.datatype(), attribute.column(),
                attribute.unit(), attribute.identifier(), attribute.codeList());
    }

    private static int saveInDependencyOrder(OntologyRepository repository, List<Concept> concepts, List<String> warnings) {
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

    private static int save(List<String> warnings, String what, Runnable action) {
        try {
            action.run();
            return 1;
        } catch (IllegalArgumentException e) {
            warnings.add("跳过" + what + "：" + e.getMessage());
            return 0;
        }
    }

    private static String matchColumn(Map<String, List<String>> tableColumns, String table, String column) {
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

    private static String findColumn(List<String> columns, String name) {
        return columns.stream().filter(column -> column.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static boolean qualified(String column) {
        return column != null && (column.indexOf('.') >= 0 || column.indexOf('．') >= 0);
    }

    /** 模型写的码值集合名、属性名或本批属性里填的旧名字，都换成属性上真正挂着的集合名。 */
    private static Map<String, String> codeListAliases(Snapshot current, Map<String, String> requestedLists) {
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

    private static Set<String> occupiedNames(Snapshot existing) {
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

    static String codeIndividualName(String requested, String codeList, String value, Set<String> used) {
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

    private static boolean usableName(String name) {
        return name != null && OntologyVocabulary.NAME.matcher(name).matches();
    }

    private static String identifierSuffix(String value) {
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

    private static List<String> columnNames(TableSnapshot table) {
        return table.getColumns().stream().map(ColumnSnapshot::getName).toList();
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }

    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @FunctionalInterface
    interface StageCaller {
        String complete(String system, String user);
    }

    @FunctionalInterface
    interface ValueLookup {
        List<String> values(String table, String column);
    }

    @FunctionalInterface
    interface DictionaryLookup {
        List<List<String>> rows(String table, String codeColumn, String labelColumn, String commentColumn);
    }

    public record DraftPhase(String phase, String message, String status, int saved, List<String> notes) {
    }

    /** @param subject 主题域，必填；本次勾选的表都归到它下面 */
    public record DraftRequest(List<String> tables, String instructions, String subject) {
    }

    public record DraftResult(
            int concepts,
            int attributes,
            int relations,
            int codes,
            int definedConcepts,
            List<String> warnings) {
    }
}
