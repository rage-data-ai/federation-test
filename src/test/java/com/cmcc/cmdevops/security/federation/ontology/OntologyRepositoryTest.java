package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

class OntologyRepositoryTest {

    private OntologyStore store;
    private OntologyRepository repository;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        repository = new OntologyRepository(store);
        OntologyFixtures.alertAsset(repository);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void snapshotRoundTripsAllKinds() {
        Snapshot snapshot = repository.snapshot();

        assertEquals(List.of("Alert", "Asset"), snapshot.concepts().stream().map(Concept::name).toList());
        Concept alert = snapshot.concepts().get(0);
        assertEquals("告警", alert.labelZh());
        assertEquals(List.of("告警事件", "安全告警"), alert.aliases());
        assertEquals("ck81.pangu.alert", alert.table());
        assertEquals("occurTime", alert.timeColumn());
        assertTrue(alert.largeTable());

        Attribute level = snapshot.attributes().stream().filter(a -> a.name().equals("alertLevel")).findFirst().orElseThrow();
        assertEquals("Alert", level.domain());
        assertEquals("integer", level.datatype());
        assertEquals("level", level.column());
        assertEquals("Ck81PanguAlert_Level", level.codeList());

        Relation affects = snapshot.relations().get(0);
        assertEquals(1, snapshot.relations().size());
        assertEquals("hasAlert", affects.inverse());
        assertEquals("assetId", affects.joinFrom());
        assertTrue(affects.functional());

        assertEquals(2, snapshot.codes().size());
        assertEquals(OntologyFixtures.ALERT_LEVEL, snapshot.codes().get(0).codeList());

        DefinedConcept highRisk = snapshot.definedConcepts().stream()
                .filter(d -> d.name().equals("HighRiskAlert")).findFirst().orElseThrow();
        assertEquals("Alert", highRisk.base());
        assertEquals(List.of(new Condition("alertLevel", ">=", "3")), highRisk.conditions());
        DefinedConcept compromised = snapshot.definedConcepts().stream()
                .filter(d -> d.name().equals("CompromisedHost")).findFirst().orElseThrow();
        assertEquals("近 24 小时高危告警数不少于 3 条的资产", compromised.definitionText());
    }

    @Test
    void reasonerSeesInverseAndDefinedSubclass() {
        OntologyQueryService query = new OntologyQueryService(store, 100);
        String prefix = "PREFIX soc: <" + OntologyVocabulary.SOC + ">\n"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n";
        assertEquals(1, query.select(prefix + "SELECT * WHERE { soc:HighRiskAlert rdfs:subClassOf soc:Alert }", true).rows().size());
        assertEquals(1, query.select(prefix + "SELECT * WHERE { soc:affectsAsset owl:inverseOf soc:hasAlert }", true).rows().size());
    }

    @Test
    void saveReplacesPreviousDescription() {
        repository.saveConcept(new Concept("Asset", "资产（新）", null, List.of("终端"), null, null, "mysql70.tsoc.asset", null, false));
        Concept asset = repository.snapshot().concepts().stream().filter(c -> c.name().equals("Asset")).findFirst().orElseThrow();
        assertEquals("资产（新）", asset.labelZh());
        assertNull(asset.labelEn());
        assertEquals(List.of("终端"), asset.aliases());
    }

    @Test
    void deleteRemovesDefinitionStructures() {
        long before = store.<Long>read(model -> model.size());
        repository.saveDefinedConcept(new DefinedConcept("MediumAlert", "中危告警", null, List.of(), null, "Alert",
                List.of(new Condition("alertLevel", "=", "2"), new Condition("alertLevel", "<", "3")), null));
        repository.delete("MediumAlert");
        long after = store.<Long>read(model -> model.size());
        assertEquals(before, after);
    }

    @Test
    void deletingConceptRemovesItsAttributes() {
        repository.delete("Alert");
        Snapshot snapshot = repository.snapshot();
        assertTrue(snapshot.concepts().stream().noneMatch(concept -> concept.name().equals("Alert")));
        assertTrue(snapshot.attributes().stream().noneMatch(attribute -> "Alert".equals(attribute.domain())));
        assertTrue(snapshot.attributes().stream().anyMatch(attribute -> attribute.name().equals("assetName")));
        assertTrue(snapshot.definedConcepts().stream().noneMatch(defined -> defined.name().equals("HighRiskAlert")));
        assertTrue(snapshot.definedConcepts().stream().anyMatch(defined -> defined.name().equals("CompromisedHost")));
        assertTrue(snapshot.codes().isEmpty());
        assertTrue(snapshot.relations().isEmpty());
        boolean valueSetLeft = store.<Boolean>read(model -> model.containsResource(
                model.getResource(OntologyVocabulary.iri(OntologyFixtures.ALERT_LEVEL))));
        assertFalse(valueSetLeft);
    }

    @Test
    void codeListNameDependsOnlyOnTableAndColumn() {
        assertEquals("Ck81PanguWlsDay01_Status", OntologyRepository.codeListName("ck81.pangu.wls_day01", "Status"));
        assertEquals("Ck81PanguWlsDay01_Status", OntologyRepository.codeListName("CK81.pangu.WLS_DAY01", "Status"));
        assertEquals("Mysql70TsocAsset_AssetType", OntologyRepository.codeListName("mysql70.tsoc.asset", "asset_type"));

        repository.saveAttribute(new Attribute("alertLevel", "告警级别", null, List.of(), null,
                "Alert", "integer", "level", null, false, "SomethingElse"));
        Attribute level = repository.snapshot().attributes().stream()
                .filter(a -> a.name().equals("alertLevel")).findFirst().orElseThrow();
        assertEquals(OntologyFixtures.ALERT_LEVEL, level.codeList());
        assertEquals(2, repository.snapshot().codes().size());
    }

    @Test
    void codeListCannotBeSharedByTwoConcepts() {
        repository.saveConcept(new Concept("AlertArchive", "告警归档", null, List.of(), null,
                null, "ck81.pangu.alert", null, false));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> repository.saveAttribute(
                new Attribute("archiveLevel", "归档级别", null, List.of(), null,
                        "AlertArchive", "integer", "level", null, false, "AlertLevel")));
        assertTrue(error.getMessage().contains("已属于概念 Alert"), error.getMessage());
        assertTrue(repository.snapshot().attributes().stream().noneMatch(a -> a.name().equals("archiveLevel")));
    }

    @Test
    void codesNeedAnAttributeThatOwnsTheirList() {
        assertThrows(IllegalArgumentException.class, () -> repository.saveCode(
                new Code("Orphan_1", "OrphanList", "1", "孤立", null, List.of(), null)));
    }

    @Test
    void clearingCodeListDropsItsCodes() {
        repository.saveAttribute(new Attribute("alertLevel", "告警级别", null, List.of(), null,
                "Alert", "integer", "level", null, false, null));
        assertTrue(repository.snapshot().codes().isEmpty());
    }

    @Test
    void deletingRelationDropsItsInverse() {
        repository.delete("affectsAsset");
        Snapshot snapshot = repository.snapshot();
        assertTrue(snapshot.relations().isEmpty());
        boolean inverseLeft = store.<Boolean>read(model -> model.containsResource(
                model.getResource(OntologyVocabulary.iri("hasAlert"))));
        assertFalse(inverseLeft);
    }

    private void subjects() {
        repository.saveConcept(new Concept("Logon", "登录", null, List.of(), null, null, "a.b.logon", null, false, "安全日志"));
        repository.saveConcept(new Concept("Host", "主机", null, List.of(), null, null, "a.b.host", null, false, "安全日志"));
        repository.saveConcept(new Concept("Rule", "规则", null, List.of(), null, null, "c.d.rule", null, false, "解析规则"));
        repository.saveAttribute(new Attribute("logon_host", "主机", null, List.of(), null,
                "Logon", "string", "host", null, false, null));
        repository.saveAttribute(new Attribute("rule_type", "类型", null, List.of(), null,
                "Rule", "string", "type", null, false, "x"));
        repository.saveCode(new Code("CDRule_Type_1", "CDRule_Type", "1", "正则", null, List.of(), null));
        repository.saveRelation(new Relation("logonHost", "登录主机", null, List.of(), null, "Logon", "Host",
                "host", "host", null, false, false));
    }

    @Test
    void onlyConceptsOfTheSameSubjectCanBeRelated() {
        subjects();

        IllegalArgumentException relation = assertThrows(IllegalArgumentException.class, () -> repository.saveRelation(
                new Relation("logonRule", null, null, List.of(), null, "Logon", "Rule", "host", "type", null, false, false)));
        assertTrue(relation.getMessage().contains("同主题域"), relation.getMessage());
        assertThrows(IllegalArgumentException.class, () -> repository.saveRelation(
                new Relation("alertRule", null, null, List.of(), null, "Alert", "Rule", "assetId", "type", null, false, false)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveConcept(
                new Concept("SubRule", null, null, List.of(), null, "Rule", null, null, false, "安全日志")));
        assertThrows(IllegalArgumentException.class, () -> repository.saveDefinedConcept(
                new DefinedConcept("RegexLogon", null, null, List.of(), null, "Logon",
                        List.of(new Condition("rule_type", "=", "1")), null)));
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class, () -> repository.saveConcept(
                new Concept("Host", "主机", null, List.of(), null, null, "a.b.host", null, false, "解析规则")));
        assertTrue(moved.getMessage().contains("Logon"), moved.getMessage());
        assertEquals(List.of("logonHost"), repository.snapshot().relations().stream().map(Relation::name)
                .filter(name -> name.startsWith("logon")).toList());
    }

    @Test
    void scopedViewOnlySeesItsSubject() {
        subjects();

        Snapshot rules = repository.within("解析规则").snapshot();
        assertEquals(List.of("Rule"), rules.concepts().stream().map(Concept::name).toList());
        assertEquals(List.of("rule_type"), rules.attributes().stream().map(Attribute::name).toList());
        assertEquals(1, rules.codes().size());
        assertTrue(rules.relations().isEmpty());
        Snapshot logs = repository.within("安全日志").snapshot();
        assertEquals(List.of("Host", "Logon"), logs.concepts().stream().map(Concept::name).toList());
        assertEquals(1, logs.relations().size());
        assertTrue(logs.codes().isEmpty());
        assertEquals(List.of("Alert", "Asset"), repository.within(null).snapshot().concepts().stream().map(Concept::name).toList());

        repository.within("解析规则").saveConcept(new Concept("Param", null, null, List.of(), null, null, null, null, false));
        assertEquals("解析规则", repository.snapshot().concepts().stream()
                .filter(concept -> concept.name().equals("Param")).findFirst().orElseThrow().subject());

        repository.renameSubject("安全日志", "Windows日志");
        assertEquals(2, repository.within("Windows日志").snapshot().concepts().size());
        assertTrue(repository.within("安全日志").snapshot().concepts().isEmpty());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> repository.saveConcept(
                new Concept("告警", null, null, null, null, null, null, null, false)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveAttribute(
                new Attribute("Alert", null, null, null, null, null, "string", null, null, false, null)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveConcept(
                new Concept("Child", null, null, null, null, "Missing", null, null, false)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveDefinedConcept(
                new DefinedConcept("Bad", null, null, null, null, "Alert",
                        List.of(new Condition("alertLevel", ">=", "high")), null)));
        assertThrows(IllegalArgumentException.class, () -> repository.saveDefinedConcept(
                new DefinedConcept("Bad", null, null, null, null, "Alert",
                        List.of(new Condition("alertLevel", "!=", "3")), null)));
    }
}
