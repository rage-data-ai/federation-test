package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Clarify;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.feedback.MemoryAskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.knowledge.MemoryAskKnowledgeStore;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.ontology.OntologyFixtures;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import tools.jackson.databind.json.JsonMapper;

class AskServiceTest {

    private static final String GOOD_SQL = "SELECT s.name, count(*) AS cnt FROM ck81.pangu.alert a "
            + "JOIN mysql70.tsoc.asset s ON a.\"assetId\" = s.id "
            + "WHERE a.\"occurTime\" >= current_timestamp - INTERVAL '1' DAY AND a.level >= 3 "
            + "GROUP BY s.name ORDER BY cnt DESC LIMIT 10";

    private OntologyStore store;
    private OntologyTools tools;
    private MetadataVerifier verifier;
    private final List<String> events = new ArrayList<>();
    private final List<Object> payloads = new ArrayList<>();
    private final List<String> executed = new ArrayList<>();
    private AskFeedbackStore feedback = new MemoryAskFeedbackStore();

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        OntologyRepository repository = new OntologyRepository(store);
        OntologyFixtures.alertAsset(repository);
        tools = new OntologyTools(repository, new OntologyQueryService(store, 100), 4);
        Map<String, TableSnapshot> metadata = Map.of(
                "ck81.pangu.alert", table("ck81", "pangu", "alert", "id", "level", "occurTime", "assetId", "name"),
                "mysql70.tsoc.asset", table("mysql70", "tsoc", "asset", "id", "name"));
        verifier = new MetadataVerifier(name -> Optional.ofNullable(metadata.get(name)), name -> {
        });
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void answersAfterGuardRejection() {
        ScriptedModel model = new ScriptedModel()
                .tool("runSql", Map.of("sql", "SELECT count(*) FROM ck81.pangu.alert WHERE level >= 3", "purpose", "main"))
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .text("""
                        {"status":"answered","answer":"web-01 最多，5 条","sql":"%s",
                         "caliber":[{"item":"高危告警","source":"ontology","detail":"level >= 3"},
                                    {"item":"最近一天","source":"user","detail":"occurTime >= now-1d"}]}
                        """.formatted(GOOD_SQL.replace("\"", "\\\"")));

        service(model, rows(List.of("web-01", 5L))).ask(new AskRequest("最近一天高危告警最多的资产名称", List.of()), this::record);

        assertEquals(List.of("run", "stage", "answerability", "ontology", "stage", "metadata", "stage", "sql", "sql", "result", "answer"), events);
        assertEquals(List.of(GOOD_SQL), executed);
        Map<?, ?> rejected = (Map<?, ?>) payloads.get(events.indexOf("sql"));
        assertEquals("rejected", rejected.get("status"));
        assertTrue(String.valueOf(rejected.get("message")).contains("occurTime"));
        assertTrue(model.toolResults().get(0).startsWith("校验未通过"));
        Map<?, ?> answer = (Map<?, ?>) payloads.get(events.lastIndexOf("answer"));
        assertEquals("web-01 最多，5 条", answer.get("answer"));
        assertEquals(List.of(List.of("web-01", 5L)), answer.get("rows"));
        assertEquals(2, ((List<?>) answer.get("caliber")).size());
        assertTrue(((List<?>) answer.get("steps")).size() >= 2);

        AskRun run = recordedRun();
        assertEquals(AskRun.ANSWERED, run.status());
        assertEquals("anonymous", run.userName());
        assertEquals("最近一天高危告警最多的资产名称", run.question());
        assertEquals("web-01 最多，5 条", run.answer());
        assertEquals(GOOD_SQL, run.sql());
        assertNull(run.error());
        assertEquals(run.id(), run.conversationId());
        assertEquals(List.of(List.of("web-01", 5L)), run.evidence().get("rows"));
        assertEquals(Set.of("ck81.pangu.alert", "mysql70.tsoc.asset"),
                Set.copyOf((List<?>) run.evidence().get("tables")));
        assertEquals(Set.of("assetName", "HighRiskAlert"), Set.copyOf((List<?>) run.evidence().get("entities")));
        assertEquals(2, ((List<?>) run.evidence().get("caliber")).size());
        assertEquals(2, ((List<?>) run.evidence().get("sqls")).size());
    }

    @Test
    void recordsDialogueHistoryUnderTheSameConversation() {
        ScriptedModel model = new ScriptedModel()
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .text("{\"status\":\"answered\",\"answer\":\"web-01\",\"sql\":null,\"caliber\":[]}");
        List<Turn> history = List.of(
                new Turn("user", "高危告警最多的资产名称"),
                new Turn("assistant", "要看哪个时间范围？"));

        service(model, rows(List.of("web-01", 5L)))
                .ask(new AskRequest("最近一天", history, "conv-42"), this::record);

        AskRun run = recordedRun();
        assertEquals("conv-42", run.conversationId());
        assertNotEquals(run.id(), run.conversationId());
        assertEquals(history, run.history());
        assertEquals("最近一天", run.question());
    }

    @Test
    void recordsRunWhenAskFails() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service(new BrokenModel(), rows())
                .ask(new AskRequest("最近一天高危告警最多的资产名称", List.of()), this::record));

        assertTrue(thrown.toString().contains("模型超时"), thrown.toString());
        AskRun run = recordedRun();
        assertEquals(AskRun.ERROR, run.status());
        assertTrue(run.error().contains("模型超时"));
        assertNull(run.answer());
        assertTrue(executed.isEmpty());
    }

    @Test
    void answerStillArrivesWhenRunCannotBeSaved() {
        feedback = new MemoryAskFeedbackStore() {
            @Override
            public void saveRun(AskRun run) {
                throw new IllegalStateException("联邦库不可用");
            }
        };
        ScriptedModel model = new ScriptedModel()
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .text("{\"status\":\"answered\",\"answer\":\"web-01\",\"sql\":null,\"caliber\":[]}");

        service(model, rows(List.of("web-01", 5L)))
                .ask(new AskRequest("最近一天高危告警最多的资产名称", List.of()), this::record);

        assertEquals("answer", events.get(events.size() - 1));
    }

    @Test
    void degenerateResultAllowsOnlyOneDiagnostic() {
        ScriptedModel model = new ScriptedModel()
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .tool("runSql", Map.of("sql", GOOD_SQL.replace(" AND a.level >= 3", ""), "purpose", "diagnostic"))
                .tool("runSql", Map.of("sql", GOOD_SQL.replace("LIMIT 10", "LIMIT 20"), "purpose", "diagnostic"))
                .text("{\"status\":\"answered\",\"answer\":\"最近一天没有高危告警\",\"sql\":null,\"caliber\":[]}");

        service(model, rows()).ask(new AskRequest("最近一天高危告警最多的资产名称", List.of()), this::record);

        assertEquals(2, executed.size());
        List<String> results = model.toolResults();
        assertTrue(results.get(0).contains("\"degenerate\":\"为空\"") && results.get(0).contains("diagnostic"));
        assertTrue(results.get(1).contains("请直接作答"));
        assertTrue(results.get(2).startsWith("退化结果已追加过 1 次诊断 SQL"));
        assertEquals("answer", events.get(events.size() - 1));
    }

    @Test
    void lowConfidenceEscalatesAndCanClarify() {
        ScriptedModel model = new ScriptedModel()
                .tool("listConcepts", Map.of())
                .text("{\"entities\":[],\"clarify\":{\"question\":\"你说的漏洞指哪类数据？\",\"options\":[\"告警\",\"资产\"]}}");

        service(model, rows()).ask(new AskRequest("漏洞趋势怎么样", List.of()), this::record);

        assertEquals("clarify", events.get(events.size() - 1));
        Clarify clarify = (Clarify) payloads.get(payloads.size() - 1);
        assertEquals(List.of("告警", "资产"), clarify.options());
        assertTrue(model.toolResults().get(0).contains("mysql70.tsoc.asset"));
        assertTrue(executed.isEmpty());

        AskRun run = recordedRun();
        assertEquals(AskRun.CLARIFY, run.status());
        assertEquals(clarify, run.evidence().get("clarify"));
        assertNull(run.answer());
    }

    @Test
    void escalationUsesEntitiesChosenByAgent() {
        ScriptedModel model = new ScriptedModel()
                .text("{\"entities\":[\"Alert\",\"alertLevel\"],\"clarify\":null}")
                .text("{\"status\":\"clarify\",\"question\":\"要看哪个时间范围？\",\"options\":[\"最近 24 小时\",\"最近 7 天\"]}");

        service(model, rows()).ask(new AskRequest("事件分布", List.of()), this::record);

        Map<?, ?> ontology = (Map<?, ?>) payloads.get(events.lastIndexOf("ontology"));
        assertEquals("explored", ontology.get("mode"));
        OntologyTools.BusinessContext context = (OntologyTools.BusinessContext) ontology.get("context");
        assertEquals("Alert", context.concepts().get(0).name());
        assertEquals("clarify", events.get(events.size() - 1));
        assertFalse(events.contains("sql"));
    }

    @Test
    void knowledgeHitCanAnswerWithoutOntology() {
        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        AskKnowledgeService knowledge = knowledgeService();
        AskKnowledge item = knowledge.create(
                "最近一天高危告警有多少？",
                "共 12 条",
                "SELECT count(*) FROM ck81.pangu.alert WHERE level >= 3",
                List.of(Map.of("item", "高危", "source", "ontology")),
                Map.of());
        ScriptedModel model = new ScriptedModel()
                .text("{\"status\":\"answered\",\"answer\":\"根据知识，共 12 条\",\"sql\":\"SELECT count(*) FROM ck81.pangu.alert WHERE level >= 3\",\"knowledgeId\":"
                        + item.id() + "}");

        service(model, rows(), knowledge).ask(new AskRequest("最近一天高危告警有多少？", List.of()), this::record);

        assertEquals(List.of("run", "stage", "knowledge", "answer"), events);
        assertFalse(events.contains("ontology"));
        assertTrue(executed.isEmpty());
        Map<?, ?> answer = (Map<?, ?>) payloads.get(events.lastIndexOf("answer"));
        assertEquals("根据知识，共 12 条", answer.get("answer"));
        assertEquals(item.id(), ((Number) answer.get("knowledgeId")).longValue());
        assertEquals(AskRun.ANSWERED, recordedRun().status());
        GatewayUser.clear();
    }

    @Test
    void knowledgeMissContinuesOntologyPath() {
        AskKnowledgeService knowledge = knowledgeService();
        ScriptedModel model = new ScriptedModel()
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .text("""
                        {"status":"answered","answer":"web-01 最多，5 条","sql":"%s",
                         "caliber":[{"item":"高危告警","source":"ontology","detail":"level >= 3"}]}
                        """.formatted(GOOD_SQL.replace("\"", "\\\"")));

        service(model, rows(List.of("web-01", 5L)), knowledge)
                .ask(new AskRequest("最近一天高危告警最多的资产名称", List.of()), this::record);

        assertFalse(events.contains("knowledge"));
        assertTrue(events.contains("ontology"));
        assertEquals("web-01 最多，5 条", ((Map<?, ?>) payloads.get(events.lastIndexOf("answer"))).get("answer"));
    }

    @Test
    void knowledgeContinueFallsThroughToOntology() {
        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        AskKnowledgeService knowledge = knowledgeService();
        knowledge.create("最近一天高危告警有多少？", "共 12 条", "SELECT 1", List.of(), Map.of());
        ScriptedModel model = new ScriptedModel()
                .text("{\"status\":\"continue\",\"reason\":\"时间范围不同\"}")
                .tool("runSql", Map.of("sql", GOOD_SQL, "purpose", "main"))
                .text("""
                        {"status":"answered","answer":"重新查库得到 5 条","sql":"%s",
                         "caliber":[{"item":"高危告警","source":"ontology","detail":"level >= 3"}]}
                        """.formatted(GOOD_SQL.replace("\"", "\\\"")));

        service(model, rows(List.of("web-01", 5L)), knowledge)
                .ask(new AskRequest("最近一天高危告警有多少？", List.of()), this::record);

        assertTrue(events.contains("knowledge"));
        assertTrue(events.contains("ontology"));
        assertEquals("重新查库得到 5 条", ((Map<?, ?>) payloads.get(events.lastIndexOf("answer"))).get("answer"));
        GatewayUser.clear();
    }

    private AskKnowledgeService knowledgeService() {
        return new AskKnowledgeService(
                new MemoryAskKnowledgeStore(),
                new KnowledgeVectorIndex(new HashingTermEmbedder(), null, JsonMapper.builder().build()),
                () -> 1_000L);
    }

    private AskService service(ChatModel model, QueryResponse response) {
        return service(model, response, null);
    }

    private AskService service(ChatModel model, QueryResponse response, AskKnowledgeService knowledge) {
        return new AskService(tools, verifier, sql -> {
            executed.add(sql);
            return response;
        }, sql -> {
        }, LlmClient.of(model), JsonMapper.builder().build(), new FederationProperties.Ask(), feedback, knowledge);
    }

    private AskRun recordedRun() {
        Map<?, ?> started = (Map<?, ?>) payloads.get(events.indexOf("run"));
        return feedback.findRun((String) started.get("runId")).orElseThrow();
    }

    private void record(String event, Object data) {
        events.add(event);
        payloads.add(data);
    }

    private static QueryResponse rows(List<Object>... rows) {
        return new QueryResponse(true, "TRINO", null, List.of(), null,
                List.of(new QueryResponse.ColumnMeta("name", "varchar"), new QueryResponse.ColumnMeta("cnt", "bigint")),
                List.of(rows), rows.length, 3, false, null);
    }

    private static TableSnapshot table(String catalog, String schema, String name, String... columns) {
        TableSnapshot table = new TableSnapshot();
        table.setCatalog(catalog);
        table.setSchema(schema);
        table.setTable(name);
        for (String column : columns) {
            table.getColumns().add(new ColumnSnapshot(column, "varchar"));
        }
        return table;
    }

    private static final class BrokenModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest request) {
            throw new IllegalStateException("模型超时");
        }
    }

    private static final class ScriptedModel implements ChatModel {

        private final Deque<AiMessage> script = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();
        private final AtomicInteger ids = new AtomicInteger();

        ScriptedModel tool(String name, Map<String, Object> arguments) {
            String json = JsonMapper.builder().build().writeValueAsString(arguments);
            script.add(AiMessage.from(ToolExecutionRequest.builder()
                    .id("call-" + ids.incrementAndGet()).name(name).arguments(json).build()));
            return this;
        }

        ScriptedModel text(String text) {
            script.add(AiMessage.from(text));
            return this;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(script.removeFirst()).build();
        }

        List<String> toolResults() {
            List<ChatMessage> last = requests.get(requests.size() - 1).messages();
            List<String> results = new ArrayList<>();
            for (ChatMessage message : last) {
                if (message instanceof ToolExecutionResultMessage result) {
                    results.add(result.text());
                }
            }
            return results;
        }
    }
}
