package com.cmcc.cmdevops.security.federation.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcUrlFactoryTest {

    @Test
    void buildMysqlUrl() {
        CatalogRecord record = new CatalogRecord();
        record.setType("mysql");
        record.setHost("10.0.0.8");
        record.setPort(3306);
        record.setDatabase("shop");
        assertTrue(JdbcUrlFactory.build(record).startsWith("jdbc:mysql://10.0.0.8:3306/shop"));
    }

    @Test
    void keepExplicitUrl() {
        CatalogRecord record = new CatalogRecord();
        record.setType("mysql");
        record.setUrl("jdbc:mysql://custom:13306/db");
        assertTrue(JdbcUrlFactory.build(record).contains("custom:13306"));
    }
}
