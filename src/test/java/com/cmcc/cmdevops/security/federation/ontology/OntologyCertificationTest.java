package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OntologyCertificationTest {

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
    void fixturesAreCertifiedAndCanToggle() {
        assertTrue(repository.snapshot().concepts().stream()
                .filter(c -> "Alert".equals(c.name())).findFirst().orElseThrow().certified());
        assertTrue(repository.snapshot().definedConcepts().stream()
                .filter(d -> "HighRiskAlert".equals(d.name())).findFirst().orElseThrow().certified());
        repository.setCertified("HighRiskAlert", false);
        assertFalse(repository.snapshot().definedConcepts().stream()
                .filter(d -> "HighRiskAlert".equals(d.name())).findFirst().orElseThrow().certified());
    }
}
