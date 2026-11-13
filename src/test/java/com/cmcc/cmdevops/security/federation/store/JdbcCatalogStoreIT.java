package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCatalogStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    @Test
    void upsertListAndDeleteCatalog() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());

        FederalDataSource dataSource = new FederalDataSource(properties, JsonMapper.builder().build());
        JdbcCatalogStore store = new JdbcCatalogStore(dataSource);

        CatalogRecord record = new CatalogRecord();
        record.setName("mysql_shop");
        record.setType("mysql");
        record.setRoute("DIRECT");
        record.setHost("db.example");
        record.setPort(3306);
        record.setDatabase("shop");
        record.setUsername("u");
        record.setPassword("p");
        store.upsert(record);

        assertEquals(1, store.list().size());
        assertEquals("mysql_shop", store.list().get(0).getName());
        assertEquals("DIRECT", store.list().get(0).getRoute());

        store.delete("mysql_shop");
        assertTrue(store.list().isEmpty());
    }
}
