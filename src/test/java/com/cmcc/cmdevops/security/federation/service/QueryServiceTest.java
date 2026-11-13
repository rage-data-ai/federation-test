package com.cmcc.cmdevops.security.federation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.cache.ResultCache;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.router.QueryRouter;
import com.cmcc.cmdevops.security.federation.security.AuditService;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.QuotaService;
import com.cmcc.cmdevops.security.federation.sql.FederatedSqlAnalyzer;
import com.cmcc.cmdevops.security.federation.sql.QueryRewriter;
import com.cmcc.cmdevops.security.federation.vector.VectorHit;
import com.cmcc.cmdevops.security.federation.vector.VectorSearchClient;

class QueryServiceTest {

    private CatalogRegistry registry;
    private QueryExecutor executor;
    private VectorSearchClient vectors;
    private AuditService audit;
    private QueryService service;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("tester"));
        FederationProperties properties = new FederationProperties();
        properties.getKafkaMappings().put("kafka.events.orders", "paimon.ods.orders");
        properties.getCache().setEnabled(false);

        registry = CatalogRegistry.inMemory();
        registry.save(catalog("mysql", "mysql", "DIRECT"), false);
        registry.save(catalog("clickhouse", "clickhouse", "DIRECT"), false);
        registry.save(catalog("paimon", "paimon", "TRINO"), false);

        executor = mock(QueryExecutor.class);
        vectors = mock(VectorSearchClient.class);
        audit = mock(AuditService.class);
        QuotaService quota = new QuotaService(properties);

        when(executor.execute(any(RoutePlan.class))).thenAnswer(invocation -> {
            RoutePlan plan = invocation.getArgument(0);
            return new QueryResponse.ResultSetData(
                    List.of(new QueryResponse.ColumnMeta("n", "bigint")),
                    List.of(List.of(1), List.of(plan.engine().name())));
        });
        when(vectors.search(anyString(), anyString(), anyList(), anyInt(), any())).thenReturn(
                List.of(new VectorHit(1, 0.99), new VectorHit(2, 0.95)));

        service = new QueryService(
                new FederatedSqlAnalyzer(properties),
                new QueryRewriter(properties),
                new QueryRouter(registry),
                executor,
                new ResultCache(properties),
                registry,
                vectors,
                quota,
                audit);
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void explainSingleSourceUsesDirectRoute() {
        QueryRequest request = new QueryRequest();
        request.setSql("SELECT * FROM mysql.shop.customer");
        request.setExplain(true);
        QueryResponse response = service.execute(request);
        assertTrue(response.success());
        assertEquals(EngineType.DIRECT.name(), response.engine());
        assertTrue(response.rewrittenSql().contains("shop.customer"));
        assertTrue(!response.rewrittenSql().contains("mysql."));
        verify(audit).log(anyString(), anyString(), any(RoutePlan.class), any(QueryResponse.class));
    }

    @Test
    void kafkaMappingRewritesBeforeRoute() {
        QueryRequest request = new QueryRequest();
        request.setSql("SELECT * FROM kafka.events.orders");
        request.setExplain(true);
        QueryResponse response = service.execute(request);
        assertEquals(EngineType.TRINO.name(), response.engine());
        assertTrue(response.rewrittenSql().contains("paimon.ods.orders"));
    }

    @Test
    void vectorSearchInjectsValues() {
        QueryRequest request = new QueryRequest();
        request.setSql("SELECT * FROM vector_search('milvus.products', 'embedding', :qvec, k => 2) v");
        request.setParameters(Map.of("qvec", List.of(1.0, 0.0, 0.0)));
        request.setExplain(true);
        QueryResponse response = service.execute(request);
        assertTrue(response.rewrittenSql().contains("VALUES"));
        assertTrue(response.rewrittenSql().contains("CAST(1 AS BIGINT)"));
        verify(vectors).search(anyString(), anyString(), anyList(), anyInt(), isNull());
    }

    @Test
    void showCatalogsHandledLocally() {
        QueryRequest request = new QueryRequest();
        request.setSql("SHOW CATALOGS");
        QueryResponse response = service.execute(request);
        assertEquals(EngineType.GATEWAY.name(), response.engine());
        assertTrue(response.rowCount() >= 2);
    }

    private static CatalogRecord catalog(String name, String type, String route) {
        CatalogRecord record = new CatalogRecord();
        record.setName(name);
        record.setType(type);
        record.setRoute(route);
        record.setHost("127.0.0.1");
        record.setDatabase("shop");
        return record;
    }
}
