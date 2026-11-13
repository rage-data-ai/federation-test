package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

@Testcontainers(disabledWithoutDocker = true)
class PgvectorTermRetrieverIT {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("ontology")
            .withUsername("federation")
            .withPassword("federation");

    @Test
    void retrievesViaCosineDistance() {
        FederationProperties.Ask ask = new FederationProperties.Ask();
        ask.setPgvectorUrl(PG.getJdbcUrl());
        ask.setPgvectorUsername(PG.getUsername());
        ask.setPgvectorPassword(PG.getPassword());
        ask.setPgvectorDimensions(64);

        try (PgvectorTermRetriever retriever = PgvectorTermRetriever.create(ask)) {
            assertTrue(retriever.enabled());
            TermEmbedder embedder = new HashingTermEmbedder(64);
            List<Term> lexicon = List.of(
                    new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0),
                    new Term("HighRiskAlert", "defined", "高危告警", "高危告警", "高危告警", "label", 0.95));
            List<Match> hits = retriever.retrieve("高危告警有多少", lexicon, embedder, 5);
            assertFalse(hits.isEmpty());
            assertTrue(hits.stream().anyMatch(m -> m.entity().equals("HighRiskAlert")));
            assertTrue(hits.get(0).source().startsWith("pgvector:"));
        }
    }
}
