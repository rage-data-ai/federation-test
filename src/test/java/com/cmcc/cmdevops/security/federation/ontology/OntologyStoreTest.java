package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OntologyStoreTest {

    private static final String NS = "http://example.org/soc#";
    private static final Resource ALERT = ResourceFactory.createResource(NS + "Alert");
    private static final Resource ALERT1 = ResourceFactory.createResource(NS + "alert1");
    private static final Property AFFECTED_BY = ResourceFactory.createProperty(NS + "affectedBy");
    private static final Resource HOST1 = ResourceFactory.createResource(NS + "host1");

    private static final String TBOX = """
            @prefix :     <http://example.org/soc#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :Alert a owl:Class .
            :HighRiskAlert a owl:Class ; rdfs:subClassOf :Alert .
            :affectsAsset a owl:ObjectProperty .
            :affectedBy a owl:ObjectProperty ; owl:inverseOf :affectsAsset .
            :alert1 a :HighRiskAlert ; :affectsAsset :host1 .
            """;

    @Test
    void inferenceIsPersistedToNamedGraphAndSurvivesReopen(@TempDir Path dir) {
        Dataset dataset = TDB2Factory.connectDataset(dir.toString());
        OntologyStore store = new OntologyStore(dataset);
        store.write(model -> model.read(new StringReader(TBOX), null, "TTL"));

        boolean persisted = dataset.calculateRead(() -> {
            Model graph = dataset.getNamedModel(OntologyStore.INFERRED_GRAPH);
            return graph.contains(ALERT1, RDF.type, ALERT) && graph.contains(HOST1, AFFECTED_BY, ALERT1);
        });
        assertTrue(persisted);
        boolean assertedClean = dataset.calculateRead(() -> !dataset.getDefaultModel().contains(ALERT1, RDF.type, ALERT));
        assertTrue(assertedClean);
        long size = store.inferredSize();
        store.close();

        OntologyStore reopened = new OntologyStore(TDB2Factory.connectDataset(dir.toString()));
        assertEquals(size, reopened.inferredSize());
        assertTrue(reopened.inferred().contains(ALERT1, RDF.type, ALERT));
        reopened.close();
    }

    @Test
    void deletingAssertionRemovesItsInferences() {
        OntologyStore store = OntologyStore.inMemory();
        store.write(model -> model.read(new StringReader(TBOX), null, "TTL"));
        assertTrue(store.inferred().contains(ALERT1, RDF.type, ALERT));

        store.write(model -> model.removeAll(ALERT1, null, null));

        assertFalse(store.inferred().contains(ALERT1, RDF.type, ALERT));
        assertFalse(store.inferred().contains(HOST1, AFFECTED_BY, ALERT1));
        store.close();
    }

    @Test
    void existingDataWithoutInferenceGraphIsMaterializedOnOpen() {
        Dataset dataset = TDB2Factory.createDataset();
        dataset.executeWrite(() -> dataset.getDefaultModel().read(new StringReader(TBOX), null, "TTL"));

        OntologyStore store = new OntologyStore(dataset);

        assertTrue(store.inferredSize() > 0);
        assertTrue(store.inferred().contains(ALERT1, RDF.type, ALERT));
        store.close();
    }
}
