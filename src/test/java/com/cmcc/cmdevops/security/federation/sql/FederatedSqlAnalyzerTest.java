package com.cmcc.cmdevops.security.federation.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.TableRef;

class FederatedSqlAnalyzerTest {

    private FederatedSqlAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new FederatedSqlAnalyzer(new FederationProperties());
    }

    @Test
    void analyzeSingleSourceAggregation() {
        AnalyzedQuery query = analyzer.analyze(
                "SELECT n_regionkey, count(*) FROM clickhouse.tpch.nation GROUP BY 1");
        assertEquals(1, query.tables().size());
        TableRef table = query.tables().get(0);
        assertEquals("clickhouse", table.catalog());
        assertEquals("tpch", table.schema());
        assertEquals("nation", table.table());
    }

    @Test
    void analyzeCrossSourceJoin() {
        AnalyzedQuery query = analyzer.analyze("""
                SELECT c.name, n.n_name
                FROM mysql.shop.customer c
                JOIN clickhouse.tpch.nation n ON c.nationkey = n.n_nationkey
                """);
        assertEquals(2, query.catalogs().size());
        assertTrue(query.catalogs().contains("mysql"));
        assertTrue(query.catalogs().contains("clickhouse"));
    }

    @Test
    void extractVectorSearchAndKeepJoinTables() {
        AnalyzedQuery query = analyzer.analyze("""
                SELECT p.name, v.score
                FROM vector_search('milvus.products', 'embedding', :qvec, k => 100, filter => 'category == "phone"') v
                JOIN mysql.shop.products p ON p.id = v.id
                """);
        assertEquals(1, query.vectorSearches().size());
        assertEquals("milvus.products", query.vectorSearches().get(0).collection());
        assertEquals(100, query.vectorSearches().get(0).k());
        assertEquals("category == \"phone\"", query.vectorSearches().get(0).filter());
        assertTrue(query.normalizedSql().contains("__vec_0"));
        assertTrue(query.catalogs().contains("mysql"));
    }

    @Test
    void detectExplainPrefix() {
        AnalyzedQuery query = analyzer.analyze("EXPLAIN SELECT * FROM mysql.shop.customer");
        assertTrue(query.explain());
        assertEquals("mysql", query.catalogs().iterator().next());
    }
}
