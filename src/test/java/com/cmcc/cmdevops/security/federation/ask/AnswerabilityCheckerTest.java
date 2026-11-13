package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ontology.OntologyFixtures;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;

class AnswerabilityCheckerTest {

    private OntologyStore store;
    private OntologyTools tools;

    @BeforeEach
    void setUp() {
        store = OntologyStore.inMemory();
        OntologyRepository repository = new OntologyRepository(store);
        OntologyFixtures.alertAsset(repository);
        tools = new OntologyTools(repository, new OntologyQueryService(store, 100), 4);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void coveredQuestionIsAnswerable() {
        var result = tools.checkAnswerable("最近一天高危告警最多的资产");
        assertTrue(result.answerable() || result.coverage() > 0);
        assertFalse(result.matchedTerms().isEmpty());
    }

    @Test
    void uncoveredDomainIsNotAnswerable() {
        var result = tools.checkAnswerable("广告投放的 ROI 是多少");
        assertFalse(result.answerable());
        assertTrue(result.coverage() < 0.5 || !result.gaps().isEmpty());
    }
}
