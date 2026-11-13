package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ontology.OntologyFixtures;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;

class FilterValueValidatorTest {

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
    void suggestsCodeValueWhenLabelUsed() {
        BusinessContext context = tools.contextOf(Set.of("Alert", "alertLevel", "AlertLevel_3"));
        Map<String, Object> byLabel = FilterValueValidator.validate(context, "alertLevel", "高危");
        assertFalse((Boolean) byLabel.get("ok"));
        assertEquals("3", byLabel.get("suggestedValue"));

        Map<String, Object> byValue = FilterValueValidator.validate(context, "level", "3");
        assertTrue((Boolean) byValue.get("ok"));
    }

    @Test
    void scanSqlFindsLabelLiterals() {
        BusinessContext context = tools.contextOf(Set.of("Alert", "alertLevel", "AlertLevel_3"));
        List<Map<String, Object>> findings = FilterValueValidator.scanSqlLiterals(context,
                "SELECT * FROM ck81.pangu.alert WHERE level = '高危'");
        assertFalse(findings.isEmpty());
        assertEquals("3", findings.get(0).get("suggestedValue"));
    }
}
