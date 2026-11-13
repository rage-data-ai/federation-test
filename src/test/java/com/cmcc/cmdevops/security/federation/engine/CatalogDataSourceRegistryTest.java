package com.cmcc.cmdevops.security.federation.engine;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.zaxxer.hikari.HikariDataSource;

class CatalogDataSourceRegistryTest {

    @Test
    void getIsCaseInsensitiveAndRemoveClosesHikari() {
        CatalogDataSourceRegistry registry = new CatalogDataSourceRegistry();
        DataSource source = mock(DataSource.class);
        registry.put("MySQL_Shop", source);

        assertSame(source, registry.get("mysql_shop"));
        assertNull(registry.get(null));

        HikariDataSource hikari = mock(HikariDataSource.class);
        registry.put("ck", hikari);
        registry.remove("ck");
        verify(hikari).close();
        assertNull(registry.get("ck"));
    }

    @Test
    void upsertWithoutDriverRemovesEntry() {
        CatalogDataSourceRegistry registry = new CatalogDataSourceRegistry();
        DataSource source = mock(DataSource.class);
        registry.put("kafka81", source);

        CatalogRecord record = new CatalogRecord();
        record.setName("kafka81");
        record.setType("kafka");
        registry.upsert(record);

        assertNull(registry.get("kafka81"));
        registry.upsert(null);
    }
}
