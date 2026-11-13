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
        assertEquals("AlertLevel", level.codeList());

        Relation affects = snapshot.relations().get(0);
        assertEquals(1, snapshot.relations().size());
        assertEquals("hasAlert", affects.inverse());
        assertEquals("assetId", affects.joinFrom());
        assertTrue(affects.functional());

        assertEquals(2, snapshot.codes().size());
        assertEquals("AlertLevel", snapshot.codes().get(0).codeList());

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
        boolean valueSetLeft = store.<Boolean>read(model -> model.containsResource(
                model.getResource(OntologyVocabulary.iri("AlertLevel"))));
        assertFalse(valueSetLeft);
    }

    @Test
    void deletingConceptKeepsCodeListStillUsedElsewhere() {
        repository.saveAttribute(new Attribute("assetLevel", "资产级别", null, List.of(), null,
                "Asset", "integer", "level", null, false, "AlertLevel"));
        repository.delete("Alert");
        Snapshot snapshot = repository.snapshot();
        assertEquals(2, snapshot.codes().size());
        assertTrue(snapshot.codes().stream().allMatch(code -> code.codeList().equals("AlertLevel")));
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
