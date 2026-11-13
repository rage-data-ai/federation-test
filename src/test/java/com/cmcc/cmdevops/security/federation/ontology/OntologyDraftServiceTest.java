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
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
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

        assertEquals(2, result.concepts());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("RawAlert") && w.contains("已经写成概念 Alert")));
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
        assertEquals("level", saved.attributes().get(0).column());
        assertEquals("assetId", saved.relations().get(0).joinFrom());
        assertEquals("id", saved.relations().get(0).joinTo());
    }

    @Test
    void conceptNameComesFromTheTable() {
        assertEquals("WlsDay01", OntologyRepository.conceptName("ck81.pangu.wls_day01", Map.of()));
        assertEquals("WlsDay01", OntologyRepository.conceptName("ck81.pangu.wls_day01",
                Map.of("WlsDay01", "ck81.pangu.wls_day01")));
        assertEquals("PanguWlsDay01", OntologyRepository.conceptName("ck81.pangu.wls_day01",
                Map.of("WlsDay01", "mysql70.tsoc.wls_day01")));

        Map<String, List<String>> columns = Map.of("ck81.pangu.wls_day01", List.of("Time", "EventID"));
        Snapshot first = new Snapshot(List.of(new Concept("WindowsEventLog", "Windows安全日志", null, List.of(), null,
                null, "ck81.pangu.wls_day01", "Time", true)), List.of(), List.of(), List.of(), List.of());
        Snapshot second = new Snapshot(List.of(new Concept("WinSecurityLog", "Windows安全日志", null, List.of(), null,
                null, "ck81.pangu.wls_day01", "Time", true)), List.of(), List.of(), List.of(), List.of());

        OntologyDraftService.apply(repository, first, repository.snapshot(), columns);
        OntologyDraftService.apply(repository, second, repository.snapshot(), columns);

        List<Concept> concepts = repository.snapshot().concepts().stream()
                .filter(concept -> "ck81.pangu.wls_day01".equals(concept.table())).toList();
        assertEquals(1, concepts.size());
        assertEquals("WlsDay01", concepts.get(0).name());
        assertTrue(concepts.get(0).aliases().contains("WinSecurityLog"));
    }

    @Test
    void definedConditionsWrittenWithColumnNamesOrLabelsAreFixed() {
        repository.saveConcept(new Concept("WlsDay01", "Windows安全日志", null, List.of(), null,
                null, "ck81.pangu.wls_day01", null, false));
        String list = OntologyRepository.codeListName("ck81.pangu.wls_day01", "LogonType");
        Snapshot draft = new Snapshot(List.of(),
                List.of(new Attribute("logonType", "登录类型", null, List.of(), null,
                                "WlsDay01", "integer", "LogonType", null, false, "x"),
                        new Attribute("eventId", "事件编号", null, List.of(), null,
                                "WlsDay01", "integer", "EventID", null, false, null)),
                List.of(),
                List.of(new Code("x_10", "x", "10", "远程交互登录", null, List.of(), null)),
                List.of(new DefinedConcept("RemoteInteractiveLogon", "远程交互登录", null, List.of(), null, "WlsDay01",
                                List.of(new Condition("LogonType", "=", "远程交互登录")), null),
                        new DefinedConcept("LogonFailureEvent", "登录失败事件", null, List.of(), null, "WlsDay01",
                                List.of(new Condition("eventId", "=", "4625")), null)));

        DraftResult result = OntologyDraftService.apply(repository, draft, repository.snapshot(),
                Map.of("ck81.pangu.wls_day01", List.of("LogonType", "EventID")));

        assertEquals(2, result.definedConcepts(), result.warnings().toString());
        Snapshot snapshot = repository.snapshot();
        Attribute logonType = snapshot.attributes().stream()
                .filter(a -> "LogonType".equals(a.column())).findFirst().orElseThrow();
        assertEquals("wlsDay01_LogonType", logonType.name());
        assertTrue(logonType.aliases().contains("logonType"));
        assertEquals(list, logonType.codeList());
        Map<String, DefinedConcept> saved = snapshot.definedConcepts().stream()
                .collect(java.util.stream.Collectors.toMap(DefinedConcept::name, d -> d));
        assertEquals(List.of(new Condition("wlsDay01_LogonType", "=", "10")), saved.get("RemoteInteractiveLogon").conditions());
        assertEquals(List.of(new Condition("wlsDay01_EventID", "=", "4625")), saved.get("LogonFailureEvent").conditions());
    }

    @Test
    void factColumnSeesTheDimensionCodesThroughTheRelation() {
        Snapshot snapshot = new Snapshot(List.of(),
                List.of(new Attribute("wlsDay01_EventID", "事件编号", null, List.of(), null,
                                "WlsDay01", "integer", "EventID", null, false, null),
                        new Attribute("wlsEvent_EventID", "事件编号", null, List.of(), null,
                                "WlsEvent", "integer", "EventID", null, true, "Ck81PanguWlsEvent_EventID")),
                List.of(new Relation("eventDefinition", "事件定义", null, List.of(), null, "WlsDay01", "WlsEvent",
                        "EventID", "EventID", null, false, false)),
                List.of(new Code("Ck81PanguWlsEvent_EventID_4625", "Ck81PanguWlsEvent_EventID", "4625", "登录失败",
                        null, List.of(), null)),
                List.of());

        String text = OntologyDraftService.describeJoinedCodes(snapshot, "WlsDay01");
        assertTrue(text.contains("wlsDay01_EventID") && text.contains("4625=登录失败"), text);
        assertEquals(new Condition("wlsDay01_EventID", "=", "4625"), OntologyDraftService.fixCondition("FailedLogon",
                "WlsDay01", new Condition("EventID", "=", "登录失败"), snapshot, Map.of(), new ArrayList<>()));
    }

    @Test
    void legacyConceptOnTheSameTableIsNotDuplicated() {
        repository.saveConcept(new Concept("WindowsEventLog", "Windows安全日志", null, List.of(), null,
                null, "ck81.pangu.wls_day01", null, false));
        Snapshot draft = new Snapshot(List.of(new Concept("WinSecurityLog", "Windows安全日志", null, List.of(), null,
                null, "ck81.pangu.wls_day01", null, false)), List.of(), List.of(), List.of(), List.of());

        DraftResult result = OntologyDraftService.apply(repository, draft, repository.snapshot(),
                Map.of("ck81.pangu.wls_day01", List.of("Time")));

        assertEquals(0, result.concepts());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("请先删除 WindowsEventLog")), result.warnings().toString());
        assertEquals(1, repository.snapshot().concepts().stream()
                .filter(concept -> "ck81.pangu.wls_day01".equals(concept.table())).count());
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
        Concept concept = repository.snapshot().concepts().stream()
                .filter(item -> "ck81.pangu.wls_day01".equals(item.table())).findFirst().orElseThrow();
        assertEquals("WlsDay01", concept.name());
        assertTrue(concept.aliases().contains("WinEvent"));
        assertTrue(repository.snapshot().attributes().stream().anyMatch(attribute -> attribute.aliases().contains("eventC10")
                && attribute.name().equals("wlsDay01_" + attribute.column()) && attribute.domain().equals("WlsDay01")));
        assertTrue(users.stream().anyMatch(user -> user.contains("domain 必须填 WlsDay01")));
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
                          {"name":"whatever","codeList":"Ck81PanguWlsDay01_EventID","value":"4624","labelZh":"登录成功"},
                          {"name":"WinEventEventID_9999","codeList":"Ck81PanguWlsDay01_EventID","value":"9999","labelZh":"不存在"}
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
        Attribute eventId = saved.attributes().stream().filter(attribute -> attribute.aliases().contains("eventId")).findFirst().orElseThrow();
        assertEquals("wlsDay01_EventID", eventId.name());
        assertEquals("integer", eventId.datatype());
        assertEquals("Ck81PanguWlsDay01_EventID", eventId.codeList());
        Attribute userName = saved.attributes().stream().filter(attribute -> attribute.name().equals("wlsDay01_UserName")).findFirst().orElseThrow();
        assertTrue(userName.codeList() == null || userName.codeList().isBlank());
        assertEquals("4624", saved.codes().get(0).value());
        assertEquals("Ck81PanguWlsDay01_EventID_4624", saved.codes().get(0).name());
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
    void codeNamesComeFromTableColumnAndValue() {
        repository.saveConcept(new Concept("WindowsEventDictionary", "事件字典", null, List.of(), null, null,
                "ck81.pangu.wls_event", null, false));
        repository.saveAttribute(new Attribute("eventId", "事件", null, List.of(), null,
                "WindowsEventDictionary", "integer", "EventID", null, false, "WindowsEventDictionaryEventID"));
        repository.saveAttribute(new Attribute("failureReason", "失败原因", null, List.of(), null,
                "WindowsEventDictionary", "string", "FailureReason", null, false, "logonFailureReason"));
        Snapshot draft = new Snapshot(List.of(), List.of(), List.of(), List.of(
                new Code("eventId", "Ck81PanguWlsEvent_EventID", "4624", "登录成功", null, List.of(), null),
                new Code("eventId", "Ck81PanguWlsEvent_EventID", "4625", "登录失败", null, List.of(), null),
                new Code("logonFailureReason", "failureReason", "0xC000006A", "密码错误", null, List.of(), null),
                new Code("Unknown_1", "UnknownList", "1", "未知", null, List.of(), null)),
                List.of());
        Map<String, Set<String>> allowed = Map.of(
                "Ck81PanguWlsEvent_EventID", Set.of("4624", "4625"),
                "Ck81PanguWlsEvent_FailureReason", Set.of("0xC000006A"));

        DraftResult result = OntologyDraftService.apply(repository, draft, repository.snapshot(), Map.of(), Map.of(), allowed);

        assertEquals(3, result.codes(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("UnknownList")));
        List<String> names = repository.snapshot().codes().stream().map(Code::name).toList();
        assertEquals(List.of("Ck81PanguWlsEvent_EventID_4624", "Ck81PanguWlsEvent_EventID_4625",
                "Ck81PanguWlsEvent_FailureReason_0xC000006A"), names.stream().sorted().toList());

        DraftResult again = OntologyDraftService.apply(repository, draft, repository.snapshot(), Map.of(), Map.of(), allowed);
        assertEquals(3, again.codes(), again.warnings().toString());
        assertEquals(3, repository.snapshot().codes().size());
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
        assertEquals("DemoRefLookup_EvtNo", code.codeList());
        assertEquals("DemoRefLookup_EvtNo_4624", code.name());
        Attribute itemNo = repository.snapshot().attributes().stream()
                .filter(attribute -> attribute.aliases().contains("itemNo")).findFirst().orElseThrow();
        assertTrue(itemNo.name().endsWith("_evtNo"), itemNo.name());
        assertEquals("DemoRefLookup_EvtNo", itemNo.codeList());
        Attribute rowNo = repository.snapshot().attributes().stream()
                .filter(attribute -> attribute.aliases().contains("rowNo")).findFirst().orElseThrow();
        assertTrue(rowNo.name().endsWith("_evtNo"), rowNo.name());
        assertTrue(rowNo.codeList() == null || rowNo.codeList().isBlank(), rowNo.codeList());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Rows.evtNo") && warning.contains("概念 Lookup")));
    }

    @Test
    void laterTableKeepsItsOwnCodesWhenOnlyTheColumnNameMatches() {
        repository.saveConcept(new Concept("Lookup", "对照项", null, List.of(), null, null, "demo.ref.lookup", null, false));
        repository.saveAttribute(new Attribute("lookup_evtNo", "编号", null, List.of(), null,
                "Lookup", "integer", "evtNo", null, true, "DemoRefLookup_EvtNo"));
        repository.saveCode(new Code("DemoRefLookup_EvtNo_4624", "DemoRefLookup_EvtNo", "4624", "账户登录成功",
                null, List.of(), null));
        TableSnapshot fact = new TableSnapshot();
        fact.setCatalog("demo");
        fact.setSchema("log");
        fact.setTable("rows2");
        fact.setColumns(List.of(new ColumnSnapshot("occurAt", "timestamp"), new ColumnSnapshot("evtNo", "integer")));
        List<String> prompts = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            prompts.add(system);
            if (system.contains("只起草概念")) {
                return "{\"concepts\":[{\"name\":\"FactRow\",\"labelZh\":\"流水\",\"table\":\"demo.log.rows2\"}]}";
            }
            if (system.contains("只为下面这些列")) {
                return """
                        {"attributes":[
                          {"name":"rowNo","labelZh":"编号","domain":"FactRow","datatype":"integer","column":"evtNo","codeList":"OtherList"}
                        ]}
                        """;
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        OntologyDraftService.runStages(repository, JsonMapper.builder().build(), List.of(fact),
                Map.of(fact.key(), List.of()),
                Map.of("demo.ref.lookup", List.of("evtNo", "titleCn"), fact.key().toLowerCase(), List.of("occurAt", "evtNo")),
                null, caller, (ignoredTable, ignoredColumn) -> List.of(),
                (table, codeColumn, labelColumn, commentColumn) -> List.of(),
                phase -> { }, warnings);

        Attribute rowNo = repository.snapshot().attributes().stream()
                .filter(attribute -> attribute.aliases().contains("rowNo")).findFirst().orElseThrow();
        assertEquals("DemoLogRows2_EvtNo", rowNo.codeList());
        Attribute lookup = repository.snapshot().attributes().stream()
                .filter(attribute -> attribute.name().equals("lookup_evtNo")).findFirst().orElseThrow();
        assertEquals("DemoRefLookup_EvtNo", lookup.codeList());
        assertEquals(1, repository.snapshot().codes().size());
        assertTrue(warnings.stream().noneMatch(warning -> warning.contains("沿用已有维表")), warnings.toString());
    }

    @Test
    void laterTableIsRelatedToTheExistingOntology() {
        repository.saveConcept(new Concept("WlsDay01", "日志", null, List.of(), null, null, "ck81.pangu.wls_day01", null, false));
        repository.saveConcept(new Concept("WlsEvent", "事件定义", null, List.of(), null, null, "ck81.pangu.wls_event", null, false));
        repository.saveAttribute(new Attribute("wlsDay01_EventID", "事件编号", null, List.of(), null,
                "WlsDay01", "integer", "EventID", null, false, null));
        repository.saveAttribute(new Attribute("wlsEvent_EventID", "事件编号", null, List.of(), null,
                "WlsEvent", "integer", "EventID", null, true, null));
        repository.saveRelation(new Relation("eventDefinition", "事件定义", null, List.of(), null, "WlsDay01", "WlsEvent",
                "EventID", "EventID", null, false, false));
        TableSnapshot added = new TableSnapshot();
        added.setCatalog("ck81");
        added.setSchema("pangu");
        added.setTable("wls_day02");
        added.setColumns(List.of(new ColumnSnapshot("Time", "UInt32"), new ColumnSnapshot("EventID", "UInt16")));
        List<String> relationPrompts = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            if (system.contains("只起草概念")) {
                return "{\"concepts\":[{\"name\":\"Day2\",\"labelZh\":\"日志二\",\"table\":\"ck81.pangu.wls_day02\"}]}";
            }
            if (system.contains("只为下面这些列")) {
                return "{\"attributes\":[{\"name\":\"eventId\",\"labelZh\":\"事件\",\"domain\":\"Day2\",\"datatype\":\"integer\",\"column\":\"EventID\"}]}";
            }
            if (system.contains("起草这些概念之间")) {
                relationPrompts.add(user);
                return """
                        {"relations":[
                          {"name":"eventDefinition","labelZh":"事件定义","domain":"Day2","range":"WlsEvent","joinFrom":"EventID","joinTo":"EventID"},
                          {"name":"logEvent","labelZh":"日志事件","domain":"WlsDay01","range":"WlsEvent","joinFrom":"EventID","joinTo":"EventID"}
                        ]}
                        """;
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        OntologyDraftService.runStages(repository, JsonMapper.builder().build(), List.of(added),
                Map.of(added.key(), List.of()),
                Map.of("ck81.pangu.wls_day01", List.of("Time", "EventID"), "ck81.pangu.wls_event", List.of("EventID", "name"),
                        added.key().toLowerCase(), List.of("Time", "EventID")),
                null, caller, (ignoredTable, ignoredColumn) -> List.of(), phase -> { }, warnings);

        String prompt = relationPrompts.get(0);
        assertTrue(prompt.contains("本次新加的概念：WlsDay02"), prompt);
        assertTrue(prompt.contains("eventDefinition：WlsDay01.EventID = WlsEvent.EventID"), prompt);
        assertTrue(prompt.contains("domain=WlsDay02，range=WlsEvent") || prompt.contains("domain=WlsEvent，range=WlsDay02"), prompt);
        assertTrue(!prompt.contains("domain=WlsDay01，range=WlsEvent，joinFrom"), prompt);
        Map<String, Relation> relations = repository.snapshot().relations().stream()
                .collect(java.util.stream.Collectors.toMap(Relation::name, relation -> relation));
        assertEquals(2, relations.size(), warnings.toString());
        assertEquals("WlsDay01", relations.get("eventDefinition").domain());
        assertEquals("WlsDay02", relations.get("wlsDay02_eventDefinition").domain());
        assertEquals("WlsEvent", relations.get("wlsDay02_eventDefinition").range());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("logEvent") && warning.contains("eventDefinition")),
                warnings.toString());
    }

    private static final List<String> WIDE = List.of("occurAt", "evtNo", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8");

    @Test
    void newDictionaryTakesOverTheCodesOfAnExistingColumn() {
        repository.saveConcept(new Concept("Rows", "流水", null, List.of(), null, null, "demo.log.rows", null, false));
        repository.saveAttribute(new Attribute("rows_evtNo", "编号", null, List.of(), null,
                "Rows", "integer", "evtNo", null, false, "DemoLogRows_EvtNo"));
        repository.saveCode(new Code("DemoLogRows_EvtNo_4624", "DemoLogRows_EvtNo", "4624", "模型写的登录", null, List.of(), null));
        repository.saveCode(new Code("DemoLogRows_EvtNo_9999", "DemoLogRows_EvtNo", "9999", "其它", null, List.of(), null));
        TableSnapshot dictionary = new TableSnapshot();
        dictionary.setCatalog("demo");
        dictionary.setSchema("ref");
        dictionary.setTable("lookup");
        dictionary.setColumns(List.of(new ColumnSnapshot("evtNo", "UInt16"), new ColumnSnapshot("titleCn", "String")));
        List<String> definedPrompts = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            if (system.contains("只起草概念")) {
                return "{\"concepts\":[{\"name\":\"Item\",\"labelZh\":\"对照项\",\"table\":\"demo.ref.lookup\"}]}";
            }
            if (system.contains("只为下面这些列")) {
                return """
                        {"attributes":[
                          {"name":"itemNo","labelZh":"编号","domain":"Item","datatype":"integer","column":"evtNo"},
                          {"name":"itemTitle","labelZh":"标题","domain":"Item","datatype":"string","column":"titleCn"}
                        ]}
                        """;
            }
            if (system.contains("起草这些概念之间")) {
                return "{\"relations\":[{\"name\":\"itemOf\",\"labelZh\":\"对照\",\"domain\":\"Rows\",\"range\":\"Lookup\",\"joinFrom\":\"evtNo\",\"joinTo\":\"evtNo\"}]}";
            }
            if (system.contains("只起草派生概念")) {
                definedPrompts.add(user);
                if (user.contains("本次只为概念 Rows")) {
                    return "{\"definedConcepts\":[{\"name\":\"LogonRow\",\"labelZh\":\"登录流水\",\"base\":\"Rows\",\"conditions\":[{\"attribute\":\"evtNo\",\"operator\":\"=\",\"value\":\"账户登录成功\"}]}]}";
                }
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        OntologyDraftService.runStages(repository, JsonMapper.builder().build(), List.of(dictionary),
                Map.of(dictionary.key(), List.of(List.of("4624", "账户登录成功"))),
                Map.of("demo.log.rows", WIDE, dictionary.key().toLowerCase(), List.of("evtNo", "titleCn")),
                null, caller, (ignoredTable, ignoredColumn) -> List.of(),
                (table, codeColumn, labelColumn, commentColumn) -> List.of(List.of("4624", "账户登录成功")),
                phase -> { }, warnings);

        Snapshot saved = repository.snapshot();
        Attribute fact = saved.attributes().stream().filter(a -> a.name().equals("rows_evtNo")).findFirst().orElseThrow();
        assertTrue(fact.codeList() == null || fact.codeList().isBlank(), fact.codeList() + " " + warnings);
        assertEquals(List.of("DemoRefLookup_EvtNo"), saved.codes().stream().map(Code::codeList).distinct().toList());
        assertEquals("账户登录成功", saved.codes().get(0).labelZh());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Rows.evtNo") && warning.contains("9999")), warnings.toString());
        assertTrue(definedPrompts.stream().anyMatch(prompt -> prompt.contains("本次只为概念 Rows")
                && prompt.contains("4624=账户登录成功")), definedPrompts.toString());
        assertEquals(List.of(new Condition("rows_evtNo", "=", "4624")), saved.definedConcepts().stream()
                .filter(defined -> defined.name().equals("LogonRow")).findFirst().orElseThrow().conditions());
    }

    @Test
    void newTableJoinedToAnExistingDictionaryDropsItsOwnCodes() {
        repository.saveConcept(new Concept("Lookup", "对照项", null, List.of(), null, null, "demo.ref.lookup", null, false));
        repository.saveAttribute(new Attribute("lookup_evtNo", "编号", null, List.of(), null,
                "Lookup", "integer", "evtNo", null, true, "DemoRefLookup_EvtNo"));
        repository.saveAttribute(new Attribute("lookup_titleCn", "标题", null, List.of(), null,
                "Lookup", "string", "titleCn", null, false, null));
        repository.saveCode(new Code("DemoRefLookup_EvtNo_4624", "DemoRefLookup_EvtNo", "4624", "账户登录成功",
                null, List.of(), null));
        TableSnapshot fact = new TableSnapshot();
        fact.setCatalog("demo");
        fact.setSchema("log");
        fact.setTable("rows2");
        fact.setColumns(WIDE.stream().map(name -> new ColumnSnapshot(name, "UInt32")).toList());
        List<String> definedPrompts = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            if (system.contains("只起草概念")) {
                return "{\"concepts\":[{\"name\":\"FactRow\",\"labelZh\":\"流水\",\"table\":\"demo.log.rows2\"}]}";
            }
            if (system.contains("只为下面这些列") && user.contains("evtNo")) {
                return "{\"attributes\":[{\"name\":\"rowNo\",\"labelZh\":\"编号\",\"domain\":\"FactRow\",\"datatype\":\"integer\",\"column\":\"evtNo\",\"codeList\":\"x\"}]}";
            }
            if (system.contains("每一个值都要写一条码值")) {
                return "{\"codes\":[{\"name\":\"a\",\"codeList\":\"DemoLogRows2_EvtNo\",\"value\":\"4624\",\"labelZh\":\"模型写的登录\"}]}";
            }
            if (system.contains("起草这些概念之间")) {
                return "{\"relations\":[{\"name\":\"itemOf\",\"labelZh\":\"对照\",\"domain\":\"Rows2\",\"range\":\"Lookup\",\"joinFrom\":\"evtNo\",\"joinTo\":\"evtNo\"}]}";
            }
            if (system.contains("只起草派生概念")) {
                definedPrompts.add(user);
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        OntologyDraftService.runStages(repository, JsonMapper.builder().build(), List.of(fact),
                Map.of(fact.key(), List.of()),
                Map.of("demo.ref.lookup", List.of("evtNo", "titleCn"), fact.key().toLowerCase(), WIDE),
                null, caller, (table, column) -> "evtNo".equals(column) ? List.of("4624") : List.of(),
                (table, codeColumn, labelColumn, commentColumn) -> List.of(),
                phase -> { }, warnings);

        Snapshot saved = repository.snapshot();
        Attribute own = saved.attributes().stream().filter(a -> a.name().equals("rows2_evtNo")).findFirst().orElseThrow();
        assertTrue(own.codeList() == null || own.codeList().isBlank(), own.codeList() + " " + warnings);
        assertEquals(1, saved.codes().size(), warnings.toString());
        assertEquals("账户登录成功", saved.codes().get(0).labelZh());
        assertTrue(definedPrompts.stream().anyMatch(prompt -> prompt.contains("本次只为概念 Rows2")
                && prompt.contains("4624=账户登录成功")), definedPrompts.toString());
    }

    private static TableSnapshot table(String catalog, String schema, String name, String... columns) {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog(catalog);
        table.setSchema(schema);
        table.setTable(name);
        table.setColumns(java.util.Arrays.stream(columns).map(column -> new ColumnSnapshot(column, "String")).toList());
        return table;
    }

    @Test
    void draftingStaysInsideItsSubject() {
        repository.saveConcept(new Concept("Rule", "解析规则", null, List.of(), null, null, "c.d.rule", null, false, "解析规则"));
        repository.saveAttribute(new Attribute("rule_ruleId", "规则编号", null, List.of(), null,
                "Rule", "string", "ruleId", null, true, null));
        repository.saveDefinedConcept(new DefinedConcept("Special", "特殊规则", null, List.of(), null, "Rule",
                List.of(new Condition("rule_ruleId", "=", "1")), null));
        TableSnapshot logon = table("a", "b", "logon", "ruleId", "occurAt");
        TableSnapshot rule = table("a", "b", "rule", "ruleId", "title");
        List<String> seen = new ArrayList<>();
        OntologyDraftService.StageCaller caller = (system, user) -> {
            seen.add(user);
            if (system.contains("只起草概念")) {
                return """
                        {"concepts":[
                          {"name":"LogonEvent","labelZh":"登录","table":"a.b.logon"},
                          {"name":"LogRule","labelZh":"日志规则","table":"a.b.rule"}
                        ]}
                        """;
            }
            if (system.contains("只为下面这些列") && user.contains("a.b.logon")) {
                return "{\"attributes\":[{\"name\":\"logonRule\",\"labelZh\":\"规则\",\"domain\":\"LogonEvent\",\"datatype\":\"string\",\"column\":\"ruleId\"}]}";
            }
            if (system.contains("只为下面这些列")) {
                return "{\"attributes\":[{\"name\":\"ruleNo\",\"labelZh\":\"规则\",\"domain\":\"LogRule\",\"datatype\":\"string\",\"column\":\"ruleId\"}]}";
            }
            if (system.contains("起草这些概念之间")) {
                return """
                        {"relations":[
                          {"name":"hitRule","labelZh":"命中规则","domain":"Logon","range":"LogRule","joinFrom":"ruleId","joinTo":"ruleId"},
                          {"name":"parsedBy","labelZh":"解析规则","domain":"Logon","range":"Rule","joinFrom":"ruleId","joinTo":"ruleId"}
                        ]}
                        """;
            }
            if (system.contains("只起草派生概念") && user.contains("本次只为概念 Logon")) {
                return "{\"definedConcepts\":[{\"name\":\"Special\",\"labelZh\":\"特殊登录\",\"base\":\"Logon\",\"conditions\":[{\"attribute\":\"ruleId\",\"operator\":\"=\",\"value\":\"7\"}]}]}";
            }
            return "{}";
        };
        List<String> warnings = new ArrayList<>();

        OntologyDraftService.runStages(repository, JsonMapper.builder().build(), "安全日志", List.of(logon, rule),
                Map.of(logon.key(), List.of(), rule.key(), List.of()),
                Map.of("a.b.logon", List.of("ruleId", "occurAt"), "a.b.rule", List.of("ruleId", "title"),
                        "c.d.rule", List.of("ruleId", "type")),
                null, caller, (ignoredTable, ignoredColumn) -> List.of(), null, phase -> { }, warnings);

        assertTrue(seen.stream().noneMatch(text -> text.contains("c.d.rule") || text.contains("rule_ruleId")), seen.toString());
        Snapshot all = repository.snapshot();
        Map<String, Concept> concepts = all.concepts().stream()
                .collect(java.util.stream.Collectors.toMap(Concept::name, concept -> concept));
        assertTrue(concepts.keySet().containsAll(List.of("Rule", "Logon", "BRule")), concepts.keySet() + " " + warnings);
        assertEquals("安全日志", concepts.get("Logon").subject());
        assertEquals("安全日志", concepts.get("BRule").subject());
        assertEquals("解析规则", concepts.get("Rule").subject());
        assertEquals("c.d.rule", concepts.get("Rule").table());
        assertEquals(List.of("hitRule"), all.relations().stream().map(Relation::name).toList(), warnings.toString());
        assertEquals("BRule", all.relations().get(0).range());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("parsedBy") && warning.contains("别的主题域")),
                warnings.toString());
        Map<String, DefinedConcept> defined = all.definedConcepts().stream()
                .collect(java.util.stream.Collectors.toMap(DefinedConcept::name, item -> item));
        assertEquals("Rule", defined.get("Special").base());
        assertEquals("Logon", defined.get("Logon_Special").base());
        assertEquals(List.of(new Condition("logon_ruleId", "=", "7")), defined.get("Logon_Special").conditions());

        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class, () -> OntologyDraftService.runStages(
                repository, JsonMapper.builder().build(), "安全日志", List.of(table("c", "d", "rule", "ruleId", "type")),
                Map.of(), Map.of("c.d.rule", List.of("ruleId", "type")), null, caller,
                (ignoredTable, ignoredColumn) -> List.of(), null, phase -> { }, new ArrayList<>()));
        assertTrue(moved.getMessage().contains("解析规则"), moved.getMessage());
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
