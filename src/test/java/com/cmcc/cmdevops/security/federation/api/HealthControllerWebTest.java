package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.PgvectorTermRetriever;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class HealthControllerWebTest {

    private MockMvc mockMvc;
    private PgvectorTermRetriever pgvector;

    @BeforeEach
    void setUp() {
        FederationProperties properties = new FederationProperties();
        properties.getAsk().setEmbedStore("both");
        properties.getAsk().setEmbedEndpoint("");
        properties.getAsk().setPgvectorUrl("");
        properties.getAsk().setPgvectorIndex("hnsw");
        pgvector = mock(PgvectorTermRetriever.class);
        when(pgvector.enabled()).thenReturn(false);
        when(pgvector.activeIndex()).thenReturn("none");
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(properties, pgvector)).build();
    }

    @Test
    void healthExposesAskSignalsWithoutSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.ask.embedStore").value("both"))
                .andExpect(jsonPath("$.ask.embedEndpointConfigured").value(false))
                .andExpect(jsonPath("$.ask.pgvectorConfigured").value(false))
                .andExpect(jsonPath("$.ask.pgvectorEnabled").value(false))
                .andExpect(jsonPath("$.ask.pgvectorIndexConfigured").value("hnsw"))
                .andExpect(jsonPath("$.ask.pgvectorIndexActive").value("none"));
    }
}
