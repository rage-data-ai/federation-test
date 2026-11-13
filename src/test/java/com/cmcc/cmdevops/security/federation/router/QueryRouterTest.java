package com.cmcc.cmdevops.security.federation.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.sql.FederatedSqlAnalyzer;

class QueryRouterTest {

    private FederatedSqlAnalyzer analyzer;
    private QueryRouter router;
    private CatalogRegistry registry;

    @BeforeEach
    void setUp() {
        registry = CatalogRegistry.inMemory();
        registry.save(jdbc("mysql", "mysql"), false);
        registry.save(jdbc("clickhouse", "clickhouse"), false);
        registry.save(trinoOnly("kafka", "kafka"), false);
        analyzer = new FederatedSqlAnalyzer(new FederationProperties());
        router = new QueryRouter(registry);
    }

    @Test
    void routeSingleJdbcSourceToDirect() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM mysql.shop.customer");
        RoutePlan plan = router.route(query, query.normalizedSql());
        assertEquals(EngineType.DIRECT, plan.engine());
        assertEquals("mysql", plan.directCatalog());
    }

    @Test
    void routeCrossSourceToTrino() {
        AnalyzedQuery query = analyzer.analyze("""
                SELECT c.name, n.n_name
                FROM mysql.shop.customer c
                JOIN clickhouse.tpch.nation n ON c.nationkey = n.n_nationkey
                """);
        RoutePlan plan = router.route(query, query.normalizedSql());
        assertEquals(EngineType.TRINO, plan.engine());
    }

    @Test
    void honorEngineHintTrino() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM mysql.shop.customer");
        RoutePlan plan = router.route(query, query.normalizedSql(), "trino");
        assertEquals(EngineType.TRINO, plan.engine());
    }

    @Test
    void nonDirectTypeGoesToTrino() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM kafka.events.orders");
        RoutePlan plan = router.route(query, query.normalizedSql());
        assertEquals(EngineType.TRINO, plan.engine());
    }

    @Test
    void rejectUnregisteredCatalog() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM unknown.shop.customer");
        assertThrows(IllegalArgumentException.class, () -> router.route(query, query.normalizedSql()));
    }

    private CatalogRecord jdbc(String name, String type) {
        CatalogRecord record = new CatalogRecord();
        record.setName(name);
        record.setType(type);
        record.setRoute("DIRECT");
        record.setHost("127.0.0.1");
        record.setDatabase("shop");
        return record;
    }

    private CatalogRecord trinoOnly(String name, String type) {
        CatalogRecord record = jdbc(name, type);
        record.setRoute("TRINO");
        return record;
    }
}
