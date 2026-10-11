package com.cmcc.cmdevops.security.federation.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.vector.VectorHit;

class QueryRewriterTest {

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        FederationProperties properties = new FederationProperties();
        properties.getKafkaMappings().put("kafka.events.orders", "paimon.ods.orders");
        rewriter = new QueryRewriter(properties);
    }

    @Test
    void mapKafkaTopicToLakeTable() {
        String sql = rewriter.applyKafkaMappings(
                "SELECT * FROM kafka.events.orders o JOIN mysql.shop.customer c ON o.custkey = c.id");
        assertTrue(sql.contains("paimon.ods.orders"));
        assertTrue(sql.contains("mysql.shop.customer"));
    }

    @Test
    void injectVectorValues() {
        FederationProperties properties = new FederationProperties();
        FederatedSqlAnalyzer analyzer = new FederatedSqlAnalyzer(properties);
        AnalyzedQuery analyzed = analyzer.analyze(
                "SELECT * FROM vector_search('milvus.products', 'embedding', :qvec, k => 2) v");
        String rewritten = rewriter.injectVectorValues(analyzed, Map.of(
                analyzed.vectorSearches().get(0).placeholder(),
                List.of(new VectorHit(1, 0.99), new VectorHit(2, 0.95))));
        assertTrue(rewritten.contains("VALUES"));
        assertTrue(rewritten.contains("CAST(1 AS BIGINT)"));
        assertTrue(rewritten.contains("AS v(id, score)"));
        assertTrue(!rewritten.contains("AS v(id, score) v"));
    }

    @Test
    void stripCatalogForDirectRoute() {
        RoutePlan plan = new RoutePlan(EngineType.DIRECT, "mysql", Set.of("mysql"), "单源",
                "SELECT * FROM mysql.shop.customer");
        assertEquals("SELECT * FROM shop.customer", rewriter.applyRouteRewrite(plan.rewrittenSql(), plan));
    }
}
