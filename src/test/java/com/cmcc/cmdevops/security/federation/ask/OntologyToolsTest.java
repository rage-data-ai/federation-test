package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.DefinedView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.EntityHit;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.JoinPath;
import com.cmcc.cmdevops.security.federation.ontology.OntologyFixtures;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;

class OntologyToolsTest {

    private OntologyStore store;
    private OntologyRepository repository;
    private OntologyTools tools;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        repository = new OntologyRepository(store);
        OntologyFixtures.alertAsset(repository);
        tools = new OntologyTools(repository, new OntologyQueryService(store, 100), 4);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void contextResolvesDefinedConceptAliasAndPath() {
        BusinessContext context = tools.getBusinessContext("最近一天每个资产名称下有多少条高危告警");

        assertEquals(OntologyTools.STATUS_OK, context.status());
        assertEquals(1.0, context.confidence());
        assertEquals(List.of("HighRiskAlert", "assetName"),
                context.matches().stream().map(EntityHit::name).sorted().toList());
        assertEquals(Set.of("Alert", "Asset"),
                Set.copyOf(context.concepts().stream().map(ConceptView::name).toList()));
        DefinedView highRisk = context.definedConcepts().get(0);
        assertEquals("level >= 3", highRisk.sqlCondition());
        assertEquals("ck81.pangu.alert", highRisk.baseTable());
        JoinPath path = context.paths().get(0);
        assertEquals(1, path.steps().size());
        assertEquals("ck81.pangu.alert.assetId = mysql70.tsoc.asset.id", path.steps().get(0).join());
        assertTrue(context.unresolved().isEmpty());
    }

    @Test
    void codeLabelPullsInItsAttributeAndConcept() {
        BusinessContext context = tools.getBusinessContext("中危的告警有哪些");

        assertEquals(OntologyTools.STATUS_OK, context.status());
        assertEquals("AlertLevel_2", context.codes().get(0).name());
        assertEquals("2", context.codes().get(0).value());
        assertEquals("alertLevel", context.attributes().get(0).name());
        assertEquals("Alert", context.concepts().get(0).name());
        assertEquals("Alert", context.codes().get(0).concept());
        assertEquals("level", context.codes().get(0).column());
    }

    @Test
    void sameCodeLabelInTwoConceptsLocksToTheMentionedConcept() {
        repository.saveAttribute(new Attribute("assetLevel", "资产等级", null, List.of(), null,
                "Asset", "integer", "level", null, false, "AnyName"));
        String assetLevel = OntologyRepository.codeListName("mysql70.tsoc.asset", "level");
        repository.saveCode(new Code("AssetLevel_2", assetLevel, "2", "中危", null, List.of(), null));

        BusinessContext ambiguous = tools.getBusinessContext("中危的有多少");
        assertEquals(OntologyTools.STATUS_AMBIGUOUS, ambiguous.status());

        BusinessContext locked = tools.getBusinessContext("中危的资产有多少");
        assertEquals(OntologyTools.STATUS_OK, locked.status(), locked.warnings().toString());
        assertEquals(List.of("AssetLevel_2"), locked.codes().stream().map(code -> code.name()).toList());
        assertEquals(List.of("Asset"), locked.concepts().stream().map(ConceptView::name).toList());
        assertEquals("level", locked.codes().get(0).column());
    }

    @Test
    void aliasMatchesLowerConfidenceAndUnknownQuestionHasNoMatch() {
        BusinessContext alias = tools.getBusinessContext("主机数量");
        assertEquals(0.9, alias.confidence()); // alias 0.85 + certified 加成
        assertEquals("Asset", alias.concepts().get(0).name());

        BusinessContext none = tools.getBusinessContext("今天天气怎么样");
        assertEquals(OntologyTools.STATUS_NO_MATCH, none.status());
        assertEquals(0.0, none.confidence());
    }

    @Test
    void sameLabelOnTwoEntitiesIsAmbiguous() {
        repository.saveConcept(new Concept("Device", "设备", null, List.of(), null, null, "mysql70.tsoc.device", null, false));

        BusinessContext context = tools.getBusinessContext("设备有多少");

        assertEquals(OntologyTools.STATUS_OK, context.status());
        assertEquals(List.of("Device"), context.matches().stream().map(EntityHit::name).toList());

        repository.saveConcept(new Concept("Host", "主机", null, List.of(), null, null, "mysql70.tsoc.host", null, false));
        BusinessContext ambiguous = tools.getBusinessContext("主机有多少");
        assertEquals(OntologyTools.STATUS_OK, ambiguous.status());
        assertEquals(List.of("Host"), ambiguous.matches().stream().map(EntityHit::name).toList());

        repository.saveConcept(new Concept("Machine", "主机", null, List.of(), null, null, "mysql70.tsoc.machine", null, false));
        BusinessContext tie = tools.getBusinessContext("主机有多少");
        assertEquals(OntologyTools.STATUS_AMBIGUOUS, tie.status());
        assertEquals(0.6, tie.confidence());
        assertTrue(tie.warnings().get(0).contains("Host") && tie.warnings().get(0).contains("Machine"));
    }

    @Test
    void unmappedConceptAndMissingPathAreUnresolved() {
        repository.saveConcept(new Concept("Vendor", "厂商", null, List.of(), null, null, null, null, false));

        BusinessContext context = tools.getBusinessContext("各厂商的告警");

        assertEquals(0.85, context.confidence()); // 断连折损后 + certified 加成
        assertTrue(context.unresolved().contains("概念 Vendor 没有映射物理表"));
        assertTrue(context.unresolved().stream().anyMatch(item -> item.contains("没有关系路径")));
    }

    @Test
    void describeConceptIncludesInferredSubConcepts() {
        repository.saveConcept(new Concept("WebAlert", "Web告警", null, List.of(), null, "Alert", "ck81.pangu.alert",
                "occurTime", true));
        repository.saveConcept(new Concept("SqlInjectionAlert", "SQL注入告警", null, List.of(), null, "WebAlert",
                "ck81.pangu.alert", "occurTime", true));

        ConceptView alert = (ConceptView) tools.describeEntity("Alert");

        assertTrue(alert.subConcepts().containsAll(List.of("WebAlert", "SqlInjectionAlert", "HighRiskAlert")));
        assertEquals("AlertLevel_3", alert.attributes().stream()
                .filter(a -> a.name().equals("alertLevel")).findFirst().orElseThrow().codes().get(1).name());
    }

    @Test
    void searchRanksExactBeforePartial() {
        List<EntityHit> hits = tools.searchEntities("告警");

        assertEquals("Alert", hits.get(0).name());
        assertEquals(1.0, hits.get(0).score());
        assertTrue(hits.stream().anyMatch(hit -> hit.name().equals("HighRiskAlert") && hit.score() < 1.0));
    }
}
