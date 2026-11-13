package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCatalogStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    private static FederalDataSource dataSource;
    private static JdbcCatalogStore catalogStore;
    private static JdbcMetadataStore metadataStore;
    private static JdbcLlmStore llmStore;

    @BeforeAll
    static void openStores() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());

        dataSource = new FederalDataSource(properties, JsonMapper.builder().build());
        catalogStore = new JdbcCatalogStore(dataSource);
        metadataStore = new JdbcMetadataStore(dataSource);
        llmStore = new JdbcLlmStore(dataSource);
    }

    @BeforeEach
    void cleanTables() {
        // Isolate each test: wipe catalog-dependent rows via store APIs where possible.
        for (CatalogRecord existing : catalogStore.list()) {
            catalogStore.delete(existing.getName());
        }
        metadataStore.replaceAll(List.of());
        dataSource.jdbc().update("DELETE FROM fed_llm");
    }

    @Test
    void upsertListAndDeleteCatalog() {
        CatalogRecord record = new CatalogRecord();
        record.setName("mysql_shop");
        record.setType("mysql");
        record.setRoute("DIRECT");
        record.setHost("db.example");
        record.setPort(3306);
        record.setDatabase("shop");
        record.setUsername("u");
        record.setPassword("p");
        catalogStore.upsert(record);

        assertEquals(1, catalogStore.list().size());
        assertEquals("mysql_shop", catalogStore.list().get(0).getName());
        assertEquals("DIRECT", catalogStore.list().get(0).getRoute());

        catalogStore.delete("mysql_shop");
        assertTrue(catalogStore.list().isEmpty());
    }

    @Test
    void metadataReplaceAllAndList() {
        seedCatalog("mysql_shop");

        TableSnapshot snapshot = new TableSnapshot();
        snapshot.setCatalog("mysql_shop");
        snapshot.setSchema("public");
        snapshot.setTable("orders");
        snapshot.setCollectedAt("2026-01-01T00:00:00Z");
        snapshot.setColumns(List.of(
                new ColumnSnapshot("id", "bigint"),
                new ColumnSnapshot("amount", "decimal")));

        metadataStore.replaceAll(List.of(snapshot));

        List<TableSnapshot> listed = metadataStore.list();
        assertEquals(1, listed.size());
        assertEquals("mysql_shop", listed.get(0).getCatalog());
        assertEquals("public", listed.get(0).getSchema());
        assertEquals("orders", listed.get(0).getTable());
        assertEquals(2, listed.get(0).getColumns().size());
        assertEquals("id", listed.get(0).getColumns().get(0).getName());
        assertEquals("bigint", listed.get(0).getColumns().get(0).getType());

        metadataStore.replaceAll(List.of());
        assertTrue(metadataStore.list().isEmpty());
    }

    @Test
    void metadataReplaceCatalogAndQueryPaths() {
        seedCatalog("mysql_shop");
        seedCatalog("pg_warehouse");

        TableSnapshot orders = table("mysql_shop", "public", "orders", "id", "bigint");
        TableSnapshot items = table("mysql_shop", "public", "items", "sku", "varchar");
        TableSnapshot stock = table("pg_warehouse", "inv", "stock", "qty", "int");

        metadataStore.replaceAll(List.of(orders, items, stock));
        assertEquals(3, metadataStore.list().size());

        TableSnapshot refreshed = table("mysql_shop", "public", "orders", "order_id", "bigint");
        metadataStore.replaceCatalog("mysql_shop", List.of(refreshed));

        List<TableSnapshot> listed = metadataStore.list();
        assertEquals(2, listed.size());
        TableSnapshot mysqlOnly = listed.stream()
                .filter(s -> "mysql_shop".equals(s.getCatalog()))
                .findFirst()
                .orElseThrow();
        assertEquals("orders", mysqlOnly.getTable());
        assertEquals(1, mysqlOnly.getColumns().size());
        assertEquals("order_id", mysqlOnly.getColumns().get(0).getName());

        assertTrue(metadataStore.deleteTable("pg_warehouse", "inv", "stock"));
        assertEquals(1, metadataStore.list().size());

        metadataStore.deleteCatalog("mysql_shop");
        assertTrue(metadataStore.list().isEmpty());
    }

    @Test
    void llmWriteAndRead() {
        assertNull(llmStore.read());

        Settings written = new Settings("openai", "https://api.example/v1", "sk-test", "gpt-4o-mini");
        llmStore.write(written);

        Settings read = llmStore.read();
        assertNotNull(read);
        assertEquals("openai", read.protocol());
        assertEquals("https://api.example/v1", read.baseUrl());
        assertEquals("sk-test", read.apiKey());
        assertEquals("gpt-4o-mini", read.model());

        llmStore.write(new Settings("anthropic", "https://api.anthropic.example", "ak-2", "claude"));
        Settings updated = llmStore.read();
        assertNotNull(updated);
        assertEquals("anthropic", updated.protocol());
        assertEquals("claude", updated.model());
    }

    private void seedCatalog(String name) {
        CatalogRecord record = new CatalogRecord();
        record.setName(name);
        record.setType("mysql");
        record.setRoute("DIRECT");
        record.setHost("db.example");
        record.setPort(3306);
        record.setDatabase("shop");
        record.setUsername("u");
        record.setPassword("p");
        catalogStore.upsert(record);
    }

    private static TableSnapshot table(String catalog, String schema, String table, String column, String type) {
        TableSnapshot snapshot = new TableSnapshot();
        snapshot.setCatalog(catalog);
        snapshot.setSchema(schema);
        snapshot.setTable(table);
        snapshot.setCollectedAt("2026-01-01T00:00:00Z");
        snapshot.setColumns(List.of(new ColumnSnapshot(column, type)));
        return snapshot;
    }
}
