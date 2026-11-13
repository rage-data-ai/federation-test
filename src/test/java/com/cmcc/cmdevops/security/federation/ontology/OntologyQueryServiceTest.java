package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService.SparqlResult;

class OntologyQueryServiceTest {

    private static final String NS = "http://example.org/soc#";
    private static final String PREFIX = "PREFIX : <" + NS + ">\n";

    private static final String TBOX = """
            @prefix :     <http://example.org/soc#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            :Alert a owl:Class .
            :HighRiskAlert a owl:Class ; rdfs:subClassOf :Alert .
            :Asset a owl:Class .
            :Department a owl:Class .

            :affectsAsset a owl:ObjectProperty ; rdfs:domain :Alert ; rdfs:range :Asset .
            :affectedBy a owl:ObjectProperty ; owl:inverseOf :affectsAsset .
            :subDepartmentOf a owl:ObjectProperty , owl:TransitiveProperty .

            :alert1 a :HighRiskAlert ; :affectsAsset :host1 .
            :deptC :subDepartmentOf :deptB .
            :deptB :subDepartmentOf :deptA .
            """;

    private OntologyStore store;
    private OntologyQueryService service;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        store.write(model -> model.read(new StringReader(TBOX), null, "TTL"));
        service = new OntologyQueryService(store, 100);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void subclassMembershipOnlyAppearsWithInference() {
        String sparql = PREFIX + "SELECT ?x WHERE { ?x a :Alert }";
        assertTrue(service.select(sparql, false).rows().isEmpty());
        assertEquals(List.of(List.of(NS + "alert1")), service.select(sparql, true).rows());
    }

    @Test
    void inverseAndRangeAreInferred() {
        SparqlResult inverse = service.select(PREFIX + "SELECT ?a WHERE { :host1 :affectedBy ?a }", true);
        assertEquals(List.of(List.of(NS + "alert1")), inverse.rows());

        SparqlResult range = service.select(PREFIX + "SELECT ?t WHERE { :host1 a ?t FILTER(?t = :Asset) }", true);
        assertEquals(List.of(List.of(NS + "Asset")), range.rows());
    }

    @Test
    void transitivePropertyIsClosed() {
        SparqlResult result = service.select(PREFIX + "SELECT ?p WHERE { :deptC :subDepartmentOf ?p } ORDER BY ?p", true);
        assertEquals(List.of(List.of(NS + "deptA"), List.of(NS + "deptB")), result.rows());
    }

    @Test
    void writeRefreshesInferredView() {
        String sparql = PREFIX + "SELECT ?x WHERE { ?x a :Alert } ORDER BY ?x";
        assertEquals(1, service.select(sparql, true).rows().size());

        store.write(model -> model.read(new StringReader(
                "@prefix : <" + NS + "> . :alert2 a :HighRiskAlert ."), null, "TTL"));

        assertEquals(List.of(List.of(NS + "alert1"), List.of(NS + "alert2")), service.select(sparql, true).rows());
    }

    @Test
    void rejectsNonSelectAndBadSyntax() {
        assertThrows(IllegalArgumentException.class, () -> service.select(PREFIX + "ASK { ?s ?p ?o }", true));
        assertThrows(IllegalArgumentException.class,
                () -> service.select(PREFIX + "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", false));
        assertThrows(IllegalArgumentException.class, () -> service.select("SELECT WHERE {", false));
    }

    @Test
    void truncatesAtMaxRows() {
        OntologyQueryService limited = new OntologyQueryService(store, 1);
        SparqlResult result = limited.select("SELECT ?s WHERE { ?s ?p ?o }", false);
        assertEquals(1, result.rows().size());
        assertTrue(result.truncated());
    }
}
