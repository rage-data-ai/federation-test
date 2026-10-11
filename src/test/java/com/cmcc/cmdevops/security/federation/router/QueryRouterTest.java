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

    @BeforeEach
    void setUp() {
        CatalogRegistry registry = CatalogRegistry.inMemory();
        registry.save(trino("mysql", "mysql"), false);
        registry.save(trino("clickhouse", "clickhouse"), false);
        analyzer = new FederatedSqlAnalyzer(new FederationProperties());
        router = new QueryRouter(registry);
    }

    @Test
    void routeSingleSourceToTrino() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM mysql.shop.customer");
        RoutePlan plan = router.route(query, query.normalizedSql());
        assertEquals(EngineType.TRINO, plan.engine());
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
    void rejectUnregisteredCatalog() {
        AnalyzedQuery query = analyzer.analyze("SELECT * FROM unknown.shop.customer");
        assertThrows(IllegalArgumentException.class, () -> router.route(query, query.normalizedSql()));
    }

    private CatalogRecord trino(String name, String type) {
        CatalogRecord record = new CatalogRecord();
        record.setName(name);
        record.setType(type);
        record.setHost("127.0.0.1");
        record.setDatabase("shop");
        return record;
    }
}
