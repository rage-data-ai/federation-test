package com.cmcc.cmdevops.security.federation.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class OntologyVersionServiceTest {

    @TempDir
    Path temp;

    private OntologyStore store;
    private OntologyRepository repository;
    private OntologyVersionService versions;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        repository = new OntologyRepository(store);
        OntologyFixtures.alertAsset(repository);
        versions = new OntologyVersionService(repository, JsonMapper.builder().build(), temp);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void createListAndRestore() {
        var created = versions.create("baseline");
        assertTrue(created.id().contains("baseline") || created.fileName().contains("baseline"));
        assertTrue(created.concepts() >= 2);
        assertFalse(versions.list().isEmpty());

        repository.delete("AlertCount");
        assertTrue(repository.snapshot().definedConcepts().stream().noneMatch(d -> "AlertCount".equals(d.name())));

        versions.restore(created.id());
        assertTrue(repository.snapshot().definedConcepts().stream().anyMatch(d -> "AlertCount".equals(d.name())));
        assertEquals("COUNT(*)", repository.snapshot().definedConcepts().stream()
                .filter(d -> "AlertCount".equals(d.name()))
                .findFirst()
                .orElseThrow()
                .sqlExpression());
    }
}
