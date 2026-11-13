package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftResult;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

import tools.jackson.databind.json.JsonMapper;

class OntologyDraftServiceTest {

    private static final String REPLY = """
            好的，下面是草稿：
            ```json
            {
              "concepts": [
                {"name": "Alert", "labelZh": "告警", "aliases": ["安全告警"], "table": "ck81.pangu.alert",
                 "timeColumn": "OCCURTIME", "largeTable": true, "extra": "忽略"},
                {"name": "RawAlert", "labelZh": "原始告警", "parent": "Alert", "table": "ck81.pangu.alert"},
                {"name": "Ghost", "labelZh": "不在范围", "table": "mysql70.tsoc.other"},
                {"name": "Event", "labelZh": "事件", "table": "ck81.pangu.event", "largeTable": true}
              ],
              "attributes": [
                {"name": "alertLevel", "labelZh": "告警级别", "domain": "Alert", "datatype": "integer",
                 "column": "Level", "codeList": "AlertLevel"},
                {"name": "alertMissing", "labelZh": "不存在的列", "domain": "Alert", "column": "nope"}
              ],
              "relations": [
                {"name": "affectsAsset", "labelZh": "影响资产", "domain": "Alert", "range": "Asset",
                 "joinFrom": "assetid", "joinTo": "ID"}
              ],
              "codes": [
                {"name": "AlertLevel_3", "codeList": "AlertLevel", "value": "3", "labelZh": "高危"}
              ],
              "definedConcepts": [
                {"name": "HighRiskAlert", "labelZh": "高危告警", "base": "Alert",
                 "conditions": [{"attribute": "alertLevel", "operator": ">=", "value": "3"}]}
              ]
            }
            ```
            """;

    private OntologyStore store;
    private OntologyRepository repository;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        repository = new OntologyRepository(store);
        repository.saveConcept(new Concept("Asset", "资产", null, List.of(), null, null, "mysql70.tsoc.asset", null, false));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void appliesDraftAndFixesColumnCase() {
        Snapshot existing = repository.snapshot();
        Snapshot draft = OntologyDraftService.parseDraft(JsonMapper.builder().build(), REPLY);
        Map<String, List<String>> columns = Map.of(
                "ck81.pangu.alert", List.of("id", "level", "occurTime", "assetId"),
                "ck81.pangu.event", List.of("id", "msg"),
                "mysql70.tsoc.asset", List.of("id", "name"));

        DraftResult result = OntologyDraftService.apply(repository, draft, existing, columns);

        assertEquals(3, result.concepts());
        assertEquals(1, result.attributes());
        assertEquals(1, result.relations());
        assertEquals(1, result.codes());
        assertEquals(1, result.definedConcepts());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Ghost")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("alertMissing")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Event") && w.contains("大表")));

        Snapshot saved = repository.snapshot();
        Concept alert = saved.concepts().stream().filter(c -> c.name().equals("Alert")).findFirst().orElseThrow();
        assertEquals("occurTime", alert.timeColumn());
        assertTrue(alert.largeTable());
        Concept event = saved.concepts().stream().filter(c -> c.name().equals("Event")).findFirst().orElseThrow();
        assertFalse(event.largeTable());
        assertEquals("Alert", saved.concepts().stream().filter(c -> c.name().equals("RawAlert")).findFirst().orElseThrow().parent());
        assertEquals("level", saved.attributes().get(0).column());
        assertEquals("assetId", saved.relations().get(0).joinFrom());
        assertEquals("id", saved.relations().get(0).joinTo());
    }

    @Test
    void stringFieldsWrittenAsArraysStillParse() {
        String reply = """
                {"attributes":[{
                  "name":"eventCode","labelZh":["事件编号"],"domain":"WinEvent","datatype":"integer",
                  "column":["EventID"],"aliases":"事件","identifier":["false"]
                }]}
                """;

        Snapshot draft = OntologyDraftService.parseDraft(JsonMapper.builder().build(), reply);

        assertEquals("EventID", draft.attributes().get(0).column());
        assertEquals("事件编号", draft.attributes().get(0).labelZh());
        assertEquals(List.of("事件"), draft.attributes().get(0).aliases());
        assertFalse(draft.attributes().get(0).identifier());
    }

    @Test
    void rejectsReplyWithoutJson() {
        assertThrows(IllegalStateException.class,
                () -> OntologyDraftService.parseDraft(JsonMapper.builder().build(), "抱歉，无法完成"));
    }

    @Test
    void tablePromptListsColumnsWithSamples() {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog("ck81");
        table.setSchema("pangu");
        table.setTable("alert");
        table.setColumns(List.of(new ColumnSnapshot("id", "varchar"), new ColumnSnapshot("level", "integer")));

        String text = OntologyDraftService.describeTable(table, List.of(List.of("a1", 3), List.of("a2|x", 2)));

        assertTrue(text.contains("表 ck81.pangu.alert（2 列）"));
        assertTrue(text.contains("列名 | 类型 | 样例1 | 样例2"));
        assertTrue(text.contains("level | integer | 3 | 2"));
        assertTrue(text.contains("a2/x"));
    }

    @Test
    void draftsInStagesAndKeepsEarlierStagesWhenALaterOneFails() {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog("ck81");
        table.setSchema("pangu");
        table.setTable("wls_day01");
        List<ColumnSnapshot> columns = new ArrayList<>();
        columns.add(new ColumnSnapshot("Time", "UInt32"));
        for (int i = 1; i < 11; i++) {
            columns.add(new ColumnSnapshot("c" + i, "String"));
        }
        table.setColumns(columns);
        List<String> prompts = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            prompts.add(system);
            users.add(user);
            if (system.contains("只起草概念")) {
                return """
                        {"concepts":[{"name":"WinEvent","labelZh":"Windows事件","table":"ck81.pangu.wls_day01","timeColumn":"Time","largeTable":true}]}
                        """;
            }
            if (system.contains("只为下面这些列")) {
                String column = user.contains("c10") ? "c10" : "Time";
                String name = column.equals("Time") ? "eventTime" : "eventC10";
                return """
                        {"attributes":[{"name":"%s","labelZh":"字段","domain":"WinEvent","datatype":"string","column":"%s"}]}
                        """.formatted(name, column);
            }
            if (system.contains("只起草派生概念")) {
                throw new IllegalStateException("大模型返回的 JSON 没写完");
            }
            return "{}";
        };

        DraftResult result = OntologyDraftService.runStages(
                repository,
                JsonMapper.builder().build(),
                List.of(table),
                Map.of(table.key(), List.of()),
                Map.of(table.key().toLowerCase(), columns.stream().map(ColumnSnapshot::getName).toList()),
                null,
                caller,
                (ignoredTable, ignoredColumn) -> List.of(),
                phase -> progress.add(phase.status() + " " + phase.message()),
                new ArrayList<>());

        assertEquals(1, result.concepts());
        assertEquals(2, result.attributes());
        assertEquals(0, result.definedConcepts());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("派生概念阶段未完成")));
        assertEquals(2, prompts.stream().filter(prompt -> prompt.contains("只为下面这些列")).count());
        assertTrue(progress.stream().anyMatch(line -> line.contains("这批已写入")));
        assertTrue(progress.stream().anyMatch(line -> line.contains("没有码值列，跳过")));
        assertEquals(0, prompts.stream().filter(prompt -> prompt.contains("只起草码值") || prompt.contains("只为已有属性里填了 codeList")).count());
        assertTrue(repository.snapshot().concepts().stream().anyMatch(concept -> concept.name().equals("WinEvent")));
        assertTrue(repository.snapshot().attributes().stream().anyMatch(attribute -> attribute.name().equals("eventC10")));
        assertTrue(users.stream().anyMatch(user -> user.contains("domain 必须填 WinEvent")));
        assertTrue(progress.stream().anyMatch(line -> line.contains("关系已写入")));
        assertTrue(users.stream().anyMatch(user -> user.contains("各概念所在表的列")));
    }

    @Test
    void codesUseObservedValuesAndNumericTypesAreCorrected() {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog("ck81");
        table.setSchema("pangu");
        table.setTable("wls_day01");
        table.setColumns(List.of(
                new ColumnSnapshot("Time", "UInt32"),
                new ColumnSnapshot("EventID", "UInt16"),
                new ColumnSnapshot("UserName", "String"),
                new ColumnSnapshot("standby1", "String")));
        List<String> users = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            users.add(user);
            if (system.contains("只起草概念")) {
                return """
                        {"concepts":[{"name":"WinEvent","labelZh":"Windows事件","table":"ck81.pangu.wls_day01","timeColumn":"Time","largeTable":true}]}
                        """;
            }
            if (system.contains("只为下面这些列")) {
                return """
                        {"attributes":[
                          {"name":"eventTime","labelZh":"时间","domain":"WinEvent","datatype":"string","column":"Time"},
                          {"name":"eventId","labelZh":"事件","domain":"WinEvent","datatype":"string","column":"EventID"},
                          {"name":"userName","labelZh":"用户","domain":"WinEvent","datatype":"string","column":"UserName"}
                        ]}
                        """;
            }
            if (system.contains("每一个值都要写一条码值")) {
                return """
                        {"codes":[
                          {"name":"WinEventEventID_4624","codeList":"WinEventEventID","value":"4624","labelZh":"登录成功"},
                          {"name":"WinEventEventID_9999","codeList":"WinEventEventID","value":"9999","labelZh":"不存在"}
                        ]}
                        """;
            }
            return "{}";
        };

        DraftResult result = OntologyDraftService.runStages(
                repository,
                JsonMapper.builder().build(),
                List.of(table),
                Map.of(table.key(), List.of(List.of(1, 1100, "Administrator", "x"))),
                Map.of(table.key().toLowerCase(), List.of("Time", "EventID", "UserName", "standby1")),
                null,
                caller,
                (tableName, column) -> "EventID".equals(column) ? List.of("4624", "4625") : List.of(),
                phase -> { },
                new ArrayList<>());

        assertEquals(1, result.codes(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("9999")));
        assertTrue(users.stream().anyMatch(user -> user.contains("取值：") && user.contains("4624") && user.contains("1100")));
        assertTrue(users.stream().filter(user -> user.contains("本表业务列")).noneMatch(user -> user.contains("standby1")));
        Snapshot saved = repository.snapshot();
        Attribute eventId = saved.attributes().stream().filter(attribute -> attribute.name().equals("eventId")).findFirst().orElseThrow();
        assertEquals("integer", eventId.datatype());
        assertEquals("WinEventEventID", eventId.codeList());
        Attribute userName = saved.attributes().stream().filter(attribute -> attribute.name().equals("userName")).findFirst().orElseThrow();
        assertTrue(userName.codeList() == null || userName.codeList().isBlank());
        assertEquals("4624", saved.codes().get(0).value());
    }

    @Test
    void joinHintsUseSharedIdsAndForeignKeys() {
        Snapshot snapshot = new Snapshot(List.of(
                new Concept("WinEvent", "事件", null, List.of(), null, null, "ck81.pangu.wls_day01", "Time", true),
                new Concept("WinEventCode", "事件码", null, List.of(), null, null, "ck81.pangu.wls_event", null, false),
                new Concept("Alert", "告警", null, List.of(), null, null, "ck81.pangu.alert", null, true),
                new Concept("Asset", "资产", null, List.of(), null, null, "mysql70.tsoc.asset", null, false)),
                List.of(), List.of(), List.of(), List.of());
        String hints = OntologyDraftService.describeJoinHints(snapshot, Map.of(
                "ck81.pangu.wls_day01", List.of("EventID", "Time"),
                "ck81.pangu.wls_event", List.of("EventID", "Description"),
                "ck81.pangu.alert", List.of("id", "assetId"),
                "mysql70.tsoc.asset", List.of("id", "name")));

        assertTrue(hints.contains("domain=WinEvent，range=WinEventCode，joinFrom=EventID，joinTo=EventID"));
        assertTrue(hints.contains("domain=Alert，range=Asset，joinFrom=assetId，joinTo=id"));
        assertFalse(hints.contains("joinFrom=id，joinTo=id"));
    }

    @Test
    void codeColumnsFollowSuffixOrSampleShape() {
        assertTrue(OntologyDraftService.codeLike("orderStatus", List.of()));
        assertTrue(OntologyDraftService.codeLike("EventID", List.of("1100", "1100")));
        assertFalse(OntologyDraftService.codeLike("ProcessID", List.of("0x1478", "0x200", "0x1f4")));
        assertFalse(OntologyDraftService.codeLike("customerName", List.of("张三")));
        assertFalse(OntologyDraftService.codeLike("orderId", List.of("100021", "100022", "100023")));
    }

    @Test
    void missingColonsAndTruncatedArraysStillParse() {
        Snapshot missingColon = OntologyDraftService.parseDraft(JsonMapper.builder().build(), """
                {"attributes":[{"name","eventTime","labelZh","发生时间","domain","WinEvent","datatype","dateTime","column","Time"}]}
                """);
        assertEquals("eventTime", missingColon.attributes().get(0).name());
        assertEquals("Time", missingColon.attributes().get(0).column());
        assertEquals("WinEvent", missingColon.attributes().get(0).domain());

        Snapshot truncated = OntologyDraftService.parseDraft(JsonMapper.builder().build(), """
                {"definedConcepts":[{"name":"FailedLogon","labelZh":"登录失败","base":"WinEvent","conditions":[{"attribute":"eventId","operator":"=","value":"4625"}]}
                """);
        assertEquals("FailedLogon", truncated.definedConcepts().get(0).name());
        assertEquals("4625", truncated.definedConcepts().get(0).conditions().get(0).value());
    }

    @Test
    void codeNamesThatCollideWithAttributesAreRenamed() {
        repository.saveConcept(new Concept("WindowsEventDictionary", "事件字典", null, List.of(), null, null,
                "ck81.pangu.wls_event", null, false));
        repository.saveAttribute(new Attribute("eventId", "事件", null, List.of(), null,
                "WindowsEventDictionary", "integer", "EventID", null, false, "WindowsEventDictionaryEventID"));
        repository.saveAttribute(new Attribute("failureReason", "失败原因", null, List.of(), null,
                "WindowsEventDictionary", "string", "FailureReason", null, false, "logonFailureReason"));
        Snapshot draft = new Snapshot(List.of(), List.of(), List.of(), List.of(
                new Code("eventId", "WindowsEventDictionaryEventID", "4624", "登录成功", null, List.of(), null),
                new Code("eventId", "WindowsEventDictionaryEventID", "4625", "登录失败", null, List.of(), null),
                new Code("logonFailureReason", "logonFailureReason", "0xC000006A", "密码错误", null, List.of(), null)),
                List.of());

        DraftResult result = OntologyDraftService.apply(repository, draft, repository.snapshot(), Map.of(), Map.of(), Map.of(
                "WindowsEventDictionaryEventID", Set.of("4624", "4625"),
                "logonFailureReason", Set.of("0xC000006A")));

        assertEquals(3, result.codes(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("eventId") && warning.contains("WindowsEventDictionaryEventID_4624")));
        List<String> names = repository.snapshot().codes().stream().map(Code::name).toList();
        assertTrue(names.contains("WindowsEventDictionaryEventID_4624"));
        assertTrue(names.contains("WindowsEventDictionaryEventID_4625"));
        assertTrue(names.contains("logonFailureReason_0xC000006A"));
    }

    @Test
    void qualifiedJoinColumnsUseTheRealColumnName() {
        repository.saveConcept(new Concept("WindowsSecurityEventLog", "安全日志", null, List.of(), null, null,
                "ck81.pangu.wls_day01", "Time", true));
        repository.saveConcept(new Concept("WindowsEventDictionary", "事件字典", null, List.of(), null, null,
                "ck81.pangu.wls_event", null, false));
        Snapshot draft = new Snapshot(List.of(), List.of(), List.of(new Relation(
                "eventDictionaryRef", "事件字典", null, List.of(), null,
                "WindowsSecurityEventLog", "WindowsEventDictionary",
                "WindowsSecurityEventLog.EventID", "WindowsEventDictionary.EventID", null, false, false)),
                List.of(), List.of());

        DraftResult result = OntologyDraftService.apply(repository, draft, repository.snapshot(), Map.of(
                "ck81.pangu.wls_day01", List.of("Time", "EventID"),
                "ck81.pangu.wls_event", List.of("EventID", "name")));

        assertEquals(1, result.relations(), result.warnings().toString());
        Relation saved = repository.snapshot().relations().get(0);
        assertEquals("EventID", saved.joinFrom());
        assertEquals("EventID", saved.joinTo());
    }

    @Test
    void attributeBatchThatCannotBeParsedIsSplitAndRetried() {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog("ck81");
        table.setSchema("pangu");
        table.setTable("wls_day01");
        table.setColumns(List.of(new ColumnSnapshot("Time", "UInt32"), new ColumnSnapshot("EventID", "UInt16")));
        OntologyDraftService.StageCaller caller = (system, user) -> {
            if (system.contains("只起草概念")) {
                return """
                        {"concepts":[{"name":"WinEvent","labelZh":"Windows事件","table":"ck81.pangu.wls_day01","timeColumn":"Time","largeTable":true}]}
                        """;
            }
            if (system.contains("只为下面这些列")) {
                boolean both = user.contains("\nTime |") && user.contains("\nEventID |");
                if (both) {
                    return "{\"attributes\":[{\"name\",\"labelZh\"}]}";
                }
                if (user.contains("\nTime |")) {
                    return """
                            {"attributes":[{"name":"eventTime","labelZh":"时间","domain":"WinEvent","datatype":"dateTime","column":"Time"}]}
                            """;
                }
                return """
                        {"attributes":[{"name":"eventId","labelZh":"事件","domain":"WinEvent","datatype":"integer","column":"EventID"}]}
                        """;
            }
            if (system.contains("只起草派生概念")) {
                return "{\"definedConcepts\":[{\"name\":\"FailedLogon\",\"labelZh\":\"登录失败\",\"base\":\"WinEvent\",\"conditions\":[{\"attribute\":\"eventId\",\"operator\":\"=\",\"value\":\"4625\"}]";
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        DraftResult result = OntologyDraftService.runStages(
                repository,
                JsonMapper.builder().build(),
                List.of(table),
                Map.of(table.key(), List.of()),
                Map.of(table.key().toLowerCase(), List.of("Time", "EventID")),
                null,
                caller,
                (ignoredTable, ignoredColumn) -> List.of(),
                phase -> { },
                warnings);

        assertEquals(2, result.attributes(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("拆成")));
        assertTrue(repository.snapshot().attributes().stream().anyMatch(attribute -> attribute.column().equals("Time")));
        assertTrue(repository.snapshot().attributes().stream().anyMatch(attribute -> attribute.column().equals("EventID")));
        assertEquals(1, result.definedConcepts(), warnings.toString());
    }

    @Test
    void narrowTableTextColumnBecomesTheCodeLabel() {
        TableSnapshot dictionary = new TableSnapshot();
        dictionary.setCatalog("demo");
        dictionary.setSchema("ref");
        dictionary.setTable("lookup");
        dictionary.setColumns(List.of(
                new ColumnSnapshot("evtNo", "UInt16"),
                new ColumnSnapshot("detailText", "String"),
                new ColumnSnapshot("titleCn", "String")));
        List<ColumnSnapshot> factColumns = new ArrayList<>();
        factColumns.add(new ColumnSnapshot("occurAt", "UInt32"));
        factColumns.add(new ColumnSnapshot("evtNo", "UInt16"));
        for (int i = 0; i < 8; i++) {
            factColumns.add(new ColumnSnapshot("c" + i, "String"));
        }
        TableSnapshot fact = new TableSnapshot();
        fact.setCatalog("demo");
        fact.setSchema("log");
        fact.setTable("rows");
        fact.setColumns(factColumns);
        List<String> prompts = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            prompts.add(system);
            if (system.contains("只起草概念")) {
                return """
                        {"concepts":[
                          {"name":"LookupItem","labelZh":"对照项","table":"demo.ref.lookup"},
                          {"name":"FactRow","labelZh":"流水","table":"demo.log.rows","timeColumn":"occurAt","largeTable":true}
                        ]}
                        """;
            }
            if (system.contains("只为下面这些列") && user.contains("demo.ref.lookup")) {
                return """
                        {"attributes":[
                          {"name":"itemNo","labelZh":"编号","domain":"LookupItem","datatype":"integer","column":"evtNo"},
                          {"name":"itemDetail","labelZh":"说明","domain":"LookupItem","datatype":"string","column":"detailText"},
                          {"name":"itemTitle","labelZh":"标题","domain":"LookupItem","datatype":"string","column":"titleCn"}
                        ]}
                        """;
            }
            if (system.contains("只为下面这些列") && user.contains("evtNo")) {
                return """
                        {"attributes":[
                          {"name":"rowNo","labelZh":"编号","domain":"FactRow","datatype":"integer","column":"evtNo","codeList":"OtherList"}
                        ]}
                        """;
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();
        List<Object> sample = List.of("4624", "An account was successfully logged on and remained active", "账户登录成功");

        DraftResult result = OntologyDraftService.runStages(
                repository,
                JsonMapper.builder().build(),
                List.of(dictionary, fact),
                Map.of(dictionary.key(), List.of(sample), fact.key(), List.of()),
                Map.of(
                        dictionary.key().toLowerCase(), List.of("evtNo", "detailText", "titleCn"),
                        fact.key().toLowerCase(), factColumns.stream().map(ColumnSnapshot::getName).toList()),
                null,
                caller,
                (ignoredTable, ignoredColumn) -> List.of(),
                (table, codeColumn, labelColumn, commentColumn) -> {
                    if (!dictionary.key().equals(table)) {
                        return List.of();
                    }
                    assertEquals("evtNo", codeColumn);
                    assertEquals("titleCn", labelColumn);
                    assertEquals("detailText", commentColumn);
                    return List.of(List.of("4624", "账户登录成功", "An account was successfully logged on and remained active"));
                },
                phase -> { },
                warnings);

        assertEquals(1, result.codes(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("titleCn") && warning.contains("不再让模型编写中文")));
        assertTrue(prompts.stream().noneMatch(prompt -> prompt.contains("每一个值都要写一条码值")));
        Code code = repository.snapshot().codes().get(0);
        assertEquals("4624", code.value());
        assertEquals("账户登录成功", code.labelZh());
        assertEquals("LookupItemEvtNo", code.codeList());
        Attribute linked = repository.snapshot().attributes().stream()
                .filter(attribute -> attribute.name().equals("rowNo")).findFirst().orElseThrow();
        assertEquals("LookupItemEvtNo", linked.codeList());
    }

    @Test
    void joinUsesTheShorterTextColumnOnTheNarrowSide() {
        TableSnapshot dictionary = new TableSnapshot();
        dictionary.setCatalog("demo");
        dictionary.setSchema("ref");
        dictionary.setTable("lookup");
        dictionary.setColumns(List.of(
                new ColumnSnapshot("evtNo", "UInt16"),
                new ColumnSnapshot("detailText", "String"),
                new ColumnSnapshot("titleCn", "String")));
        Snapshot snapshot = new Snapshot(List.of(
                new Concept("FactRow", "流水", null, List.of(), null, null, "demo.log.rows", "occurAt", true),
                new Concept("LookupItem", "对照项", null, List.of(), null, null, "demo.ref.lookup", null, false)),
                List.of(),
                List.of(new Relation("lookupRef", "对照", null, List.of(), null,
                        "FactRow", "LookupItem", "evtNo", "evtNo", null, false, false)),
                List.of(), List.of());
        List<String> factColumns = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            factColumns.add("c" + i);
        }
        factColumns.add("evtNo");

        List<OntologyDraftService.Dimension> found = OntologyDraftService.findDimensions(snapshot, List.of(dictionary), Map.of(
                "demo.log.rows", factColumns,
                "demo.ref.lookup", List.of("evtNo", "detailText", "titleCn")),
                Map.of(dictionary.key(), List.of(List.of("4624", "An account was successfully logged on and remained active", "账户登录成功"))));

        assertEquals(1, found.size());
        assertEquals("LookupItem", found.get(0).concept());
        assertEquals("titleCn", found.get(0).labelColumn());
        assertEquals("detailText", found.get(0).commentColumn());
        assertEquals("evtNo", found.get(0).codeColumn());
    }
}
