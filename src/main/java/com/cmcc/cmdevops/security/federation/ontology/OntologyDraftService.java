package com.cmcc.cmdevops.security.federation.ontology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.MetadataStore;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.draft.DraftApplier;
import com.cmcc.cmdevops.security.federation.ontology.draft.DraftDescriptions;
import com.cmcc.cmdevops.security.federation.ontology.draft.DraftDimensions;
import com.cmcc.cmdevops.security.federation.ontology.draft.DraftHeuristics;
import com.cmcc.cmdevops.security.federation.ontology.draft.DraftJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OntologyDraftService {

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
            aliases 必须填业务同义词（至少 1～3 个中文或英文别名），便于问数词表命中；码值也要给常用别名。
            派生概念优先写可用的 conditions（可编译为 sqlCondition），少用纯自然语言 definitionText。
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
        String tablesText = DraftDescriptions.describeTables(tables, samples);
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
                    DraftDescriptions.describeExisting(repository.snapshot()) + tablesText + note,
                    caller, warnings);
        } catch (IllegalStateException first) {
            warnings.add("概念阶段输出不完整，改为只按列名重试");
            concepts = applyStage(repository, objectMapper, columns, physicalTypes, null, "concepts",
                    stagePrompt(conceptTask, conceptShape),
                    DraftDescriptions.describeExisting(repository.snapshot()) + DraftDescriptions.describeColumnNames(tables) + note,
                    caller, warnings);
        }
        finish(progress, "concepts", "概念已写入 " + concepts.concepts() + " 个", concepts.concepts(), since(warnings, mark));

        int attributes = 0;
        String attributeShape = example(
                "{\"attributes\":[{\"name\":\"orderStatus\",\"labelZh\":\"订单状态\",\"labelEn\":\"\",\"aliases\":[],\"comment\":\"\",\"domain\":\"Order\",\"datatype\":\"string\",\"column\":\"status\",\"unit\":\"\",\"identifier\":false,\"codeList\":\"OrderStatus\"}]}");
        for (TableSnapshot table : tables) {
            String concept = conceptForTable(repository.snapshot(), table.key());
            List<ColumnSnapshot> business = DraftHeuristics.businessColumns(table.getColumns());
            if (concept.isBlank()) {
                warnings.add("跳过属性 " + table.key() + "：这张表没有写成概念");
                continue;
            }
            if (business.isEmpty()) {
                warnings.add("跳过属性 " + table.key() + "：没有可映射的列");
                continue;
            }
            List<List<ColumnSnapshot>> batches = DraftHeuristics.columnBatches(business, ATTRIBUTE_BATCH);
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
        warnings.addAll(DraftDimensions.attachSuggestedCodeLists(repository, tables, samples));
        Set<String> fromDimension = new LinkedHashSet<>();
        codeCount += DraftDimensions.loadDimensionCodes(repository, tables, samples, columns, physicalTypes, dictionaries, fromDimension, warnings);
        Map<String, Set<String>> allowedCodes = new LinkedHashMap<>();
        String evidence = DraftDescriptions.describeCodeEvidence(repository.snapshot(), tables, samples, lookup, allowedCodes, fromDimension);
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
        String joinHints = DraftDescriptions.describeJoinHints(repository.snapshot(), columns, incremental ? draftedTables : null);
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
                    DraftDescriptions.describeExisting(repository.snapshot()) + describeRelations(repository.snapshot())
                            + DraftDescriptions.describeConceptColumns(repository.snapshot(), columns) + joinHints + focus + note,
                    caller, warnings);
            relationCount = relations.relations();
            finish(progress, "relations", "关系已写入 " + relationCount + " 个", relationCount, since(warnings, mark));

            begin(progress, "relations", "按关系归并码值");
            mark = warnings.size();
            Set<String> merged = DraftDimensions.mergeCodesByRelation(repository, tables, samples, columns, physicalTypes, dictionaries, warnings);
            Snapshot afterMerge = repository.snapshot();
            for (String concept : merged) {
                if (!DraftDimensions.isDrafted(tables, DraftDescriptions.conceptTable(afterMerge, concept))) {
                    redraftDefined.add(concept);
                }
            }
            for (Relation relation : afterMerge.relations()) {
                if (earlierRelations.contains(relation.name())) {
                    continue;
                }
                for (String concept : List.of(Objects.toString(relation.domain(), ""), Objects.toString(relation.range(), ""))) {
                    if (!concept.isEmpty() && !DraftDimensions.isDrafted(tables, DraftDescriptions.conceptTable(afterMerge, concept))
                            && !DraftDescriptions.describeJoinedCodes(afterMerge, concept).isEmpty()) {
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
                        DraftDescriptions.describeExisting(repository.snapshot()) + describeJoinedCodes(repository.snapshot(), concept.name())
                                + DraftDescriptions.describeDefined(repository.snapshot(), concept.name())
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
        Snapshot parsed = DraftJson.parseDraft(objectMapper, caller.complete(system, user));
        DraftResult result = DraftApplier.apply(repository, slice(parsed, section), repository.snapshot(), columns, physicalTypes, allowedCodes);
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
                        + DraftDescriptions.describeColumns(table, batch, samples)
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

    public static String conceptForTable(Snapshot snapshot, String tableKey) {
        return snapshot.concepts().stream()
                .filter(concept -> concept.table() != null && concept.table().equalsIgnoreCase(tableKey))
                .map(Concept::name)
                .findFirst()
                .orElse("");
    }

    private static final int DICTIONARY_LIMIT = 2000;

    private static Map<String, Map<String, String>> physicalTypes(List<TableSnapshot> tables) {
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (TableSnapshot table : tables) {
            Map<String, String> columns = new LinkedHashMap<>();
            for (ColumnSnapshot column : table.getColumns() == null ? List.<ColumnSnapshot>of() : table.getColumns()) {
                if (column == null) {
                    continue;
                }
                columns.put(column.getName().toLowerCase(Locale.ROOT), column.getType());
            }
            types.put(table.key().toLowerCase(Locale.ROOT), columns);
        }
        return types;
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
    private static List<String> columnNames(TableSnapshot table) {
        return table.getColumns().stream().map(ColumnSnapshot::getName).toList();
    }
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }


    static List<ColumnSnapshot> businessColumns(List<ColumnSnapshot> columns) {
        return DraftHeuristics.businessColumns(columns);
    }

    static boolean technicalColumn(String name) {
        return DraftHeuristics.technicalColumn(name);
    }

    static boolean codeLike(String name, List<String> samples) {
        return DraftHeuristics.codeLike(name, samples);
    }

    static String normalizeDatatype(String modelType, String physical) {
        return DraftHeuristics.normalizeDatatype(modelType, physical);
    }

    static List<List<ColumnSnapshot>> columnBatches(List<ColumnSnapshot> columns, int size) {
        return DraftHeuristics.columnBatches(columns, size);
    }

    static Snapshot parseDraft(ObjectMapper objectMapper, String reply) {
        return DraftJson.parseDraft(objectMapper, reply);
    }

    static String repairJson(String json) {
        return DraftJson.repairJson(json);
    }

    static String closeTruncated(String json) {
        return DraftJson.closeTruncated(json);
    }

    static void coerceDraft(JsonNode node) {
        DraftJson.coerceDraft(node);
    }

    static String describeColumnNames(List<TableSnapshot> tables) {
        return DraftDescriptions.describeColumnNames(tables);
    }

    static String describeTables(List<TableSnapshot> tables, Map<String, List<List<Object>>> samples) {
        return DraftDescriptions.describeTables(tables, samples);
    }

    static String describeTable(TableSnapshot table, List<List<Object>> samples) {
        return DraftDescriptions.describeTable(table, samples);
    }

    static String describeColumns(TableSnapshot table, List<ColumnSnapshot> columns, List<List<Object>> samples) {
        return DraftDescriptions.describeColumns(table, columns, samples);
    }

    static String describeExisting(Snapshot existing) {
        return DraftDescriptions.describeExisting(existing);
    }

    static String describeJoinedCodes(Snapshot snapshot, String concept) {
        return DraftDescriptions.describeJoinedCodes(snapshot, concept);
    }

    static String describeDefined(Snapshot snapshot, String concept) {
        return DraftDescriptions.describeDefined(snapshot, concept);
    }

    static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            ValueLookup lookup,
            Map<String, Set<String>> allowedCodes) {
        return DraftDescriptions.describeCodeEvidence(snapshot, tables, samples, lookup, allowedCodes);
    }

    static String describeCodeEvidence(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            ValueLookup lookup,
            Map<String, Set<String>> allowedCodes,
            Set<String> coveredCodeLists) {
        return DraftDescriptions.describeCodeEvidence(snapshot, tables, samples, lookup, allowedCodes, coveredCodeLists);
    }

    static String describeRelations(Snapshot snapshot) {
        return DraftDescriptions.describeRelations(snapshot);
    }

    static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns) {
        return DraftDescriptions.describeJoinHints(snapshot, columns);
    }

    static String describeJoinHints(Snapshot snapshot, Map<String, List<String>> columns, Set<String> draftedTables) {
        return DraftDescriptions.describeJoinHints(snapshot, columns, draftedTables);
    }

    static String describeConceptColumns(Snapshot snapshot, Map<String, List<String>> columns) {
        return DraftDescriptions.describeConceptColumns(snapshot, columns);
    }

    static List<String> attachSuggestedCodeLists(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples) {
        return DraftDimensions.attachSuggestedCodeLists(repository, tables, samples);
    }

    static List<DraftDimensions.Dimension> findDimensions(
            Snapshot snapshot,
            List<TableSnapshot> tables,
            Map<String, List<String>> columns,
            Map<String, List<List<Object>>> samples) {
        return DraftDimensions.findDimensions(snapshot, tables, columns, samples);
    }

    static Set<String> mergeCodesByRelation(
            OntologyRepository repository,
            List<TableSnapshot> tables,
            Map<String, List<List<Object>>> samples,
            Map<String, List<String>> columns,
            Map<String, Map<String, String>> physicalTypes,
            DictionaryLookup dictionaries,
            List<String> warnings) {
        return DraftDimensions.mergeCodesByRelation(repository, tables, samples, columns, physicalTypes, dictionaries, warnings);
    }

    static DraftResult apply(
            OntologyRepository repository,
            Snapshot draft,
            Snapshot existing,
            Map<String, List<String>> tableColumns) {
        return DraftApplier.apply(repository, draft, existing, tableColumns);
    }

    static DraftResult apply(
            OntologyRepository repository,
            Snapshot draft,
            Snapshot existing,
            Map<String, List<String>> tableColumns,
            Map<String, Map<String, String>> physicalTypes,
            Map<String, Set<String>> allowedCodes) {
        return DraftApplier.apply(repository, draft, existing, tableColumns, physicalTypes, allowedCodes);
    }

    static Condition fixCondition(String definedName, String base, Condition condition, Snapshot snapshot,
                                  Map<String, String> renames, List<String> warnings) {
        return DraftApplier.fixCondition(definedName, base, condition, snapshot, renames, warnings);
    }

    static String codeIndividualName(String requested, String codeList, String value, Set<String> used) {
        return DraftApplier.codeIndividualName(requested, codeList, value, used);
    }

    @FunctionalInterface
    public interface StageCaller {
        String complete(String system, String user);
    }

    @FunctionalInterface
    public interface ValueLookup {
        List<String> values(String table, String column);
    }

    @FunctionalInterface
    public interface DictionaryLookup {
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
