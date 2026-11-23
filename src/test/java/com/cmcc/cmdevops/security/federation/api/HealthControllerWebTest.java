package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.PgvectorTermRetriever;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

class HealthControllerWebTest {

    private MockMvc mockMvc;
    private PgvectorTermRetriever pgvector;
    private FederalDataSource federal;

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
        KnowledgeVectorIndex knowledgeVectors = mock(KnowledgeVectorIndex.class);
        when(knowledgeVectors.milvusEnabled()).thenReturn(false);
        when(knowledgeVectors.embedderName()).thenReturn("hashing");
        when(knowledgeVectors.dim()).thenReturn(256);
        federal = mock(FederalDataSource.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(federal.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(properties, federal, pgvector, knowledgeVectors))
                .build();
    }

    @Test
    void healthExposesAskSignalsWithoutSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.federalDb").value("UP"))
                .andExpect(jsonPath("$.ask.embedStore").value("both"))
                .andExpect(jsonPath("$.ask.embedEndpointConfigured").value(false))
                .andExpect(jsonPath("$.ask.pgvectorConfigured").value(false))
                .andExpect(jsonPath("$.ask.pgvectorEnabled").value(false))
                .andExpect(jsonPath("$.ask.pgvectorIndexConfigured").value("hnsw"))
                .andExpect(jsonPath("$.ask.pgvectorIndexActive").value("none"))
                .andExpect(jsonPath("$.ask.knowledgeMilvus").value(false))
                .andExpect(jsonPath("$.ask.knowledgeEmbedder").value("hashing"))
                .andExpect(jsonPath("$.ask.knowledgeDim").value(256));
    }

    @Test
    void healthReturns503WhenFederalDbDown() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(federal.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("connection refused"));
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.federalDb").value("DOWN"));
    }
}
