package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;

/**
 * 无 Docker 的 OntologyStore 单测（内存 TDB2），覆盖 clear / materialize / inferred。
 */
class OntologyStoreTest {

    private OntologyStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void clearRemovesAssertionsAndInferences() {
        store = OntologyStore.inMemory();
        OntologyRepository repository = new OntologyRepository(store);
        repository.saveConcept(new Concept("Host", "主机", "Host", List.of(), null, null, null, null, false));
        assertTrue(store.inferred().size() >= 1);
        store.clear();
        assertTrue(store.read(model -> model.size()) == 0);
        assertTrue(store.inferredSize() == 0);
    }

    @Test
    void materializePopulatesInferredGraph() {
        store = OntologyStore.inMemory();
        OntologyRepository repository = new OntologyRepository(store);
        repository.saveConcept(new Concept("Parent", "父", null, List.of(), null, null, null, null, false));
        repository.saveConcept(new Concept("Child", "子", null, List.of(), null, "Parent", null, null, false));
        store.materialize();
        assertTrue(store.inferredSize() >= 0);
        assertTrue(store.inferred().size() > 0);
    }
}
