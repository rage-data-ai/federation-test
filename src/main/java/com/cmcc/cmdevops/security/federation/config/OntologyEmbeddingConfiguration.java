package com.cmcc.cmdevops.security.federation.config;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.CompositeOntologyEmbeddingStore;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.FileOntologyEmbeddingStore;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyEmbeddingStore;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.PgvectorTermRetriever;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;
import com.cmcc.cmdevops.security.federation.store.JdbcOntologyEmbeddingStore;

/**
 * 问数术语向量：file / jdbc / both，以及可选 pgvector（失败自动禁用）。
 */
@Configuration
public class OntologyEmbeddingConfiguration {

    @Bean
    OntologyEmbeddingStore ontologyEmbeddingStore(FederalDataSource federal, FederationProperties properties) {
        FileOntologyEmbeddingStore file = new FileOntologyEmbeddingStore(OntologyTools.embedCacheDirOf(properties));
        String mode = properties.getAsk().getEmbedStore().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "jdbc" -> new JdbcOntologyEmbeddingStore(federal);
            case "file" -> file;
            default -> new CompositeOntologyEmbeddingStore(List.of(new JdbcOntologyEmbeddingStore(federal), file));
        };
    }

    @Bean(destroyMethod = "close")
    PgvectorTermRetriever pgvectorTermRetriever(FederationProperties properties) {
        return PgvectorTermRetriever.create(properties.getAsk());
    }
}
