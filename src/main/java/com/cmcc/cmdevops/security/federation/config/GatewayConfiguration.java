package com.cmcc.cmdevops.security.federation.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cmcc.cmdevops.security.federation.vector.InMemoryVectorSearchClient;
import com.cmcc.cmdevops.security.federation.vector.MilvusRestVectorSearchClient;
import com.cmcc.cmdevops.security.federation.vector.VectorSearchClient;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class GatewayConfiguration {

    @Bean(name = "trinoDataSource")
    public DataSource trinoDataSource(FederationProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("federation-trino");
        dataSource.setJdbcUrl(properties.getTrino().getUrl());
        dataSource.setUsername(properties.getTrino().getUser());
        if (properties.getTrino().getPassword() != null && !properties.getTrino().getPassword().isBlank()) {
            dataSource.setPassword(properties.getTrino().getPassword());
        }
        dataSource.setDriverClassName("io.trino.jdbc.TrinoDriver");
        dataSource.setMaximumPoolSize(8);
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    public VectorSearchClient vectorSearchClient(FederationProperties properties) {
        String uri = properties.getVector().getMilvusUri();
        if (uri != null && !uri.isBlank()) {
            return new MilvusRestVectorSearchClient(uri);
        }
        return new InMemoryVectorSearchClient();
    }
}
