package com.cmcc.cmdevops.security.federation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.cache.ResultCache;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.CatalogView;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.model.VectorSearchCall;
import com.cmcc.cmdevops.security.federation.router.QueryRouter;
import com.cmcc.cmdevops.security.federation.security.AuditService;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.QuotaService;
import com.cmcc.cmdevops.security.federation.sql.FederatedSqlAnalyzer;
import com.cmcc.cmdevops.security.federation.sql.QueryRewriter;
import com.cmcc.cmdevops.security.federation.vector.VectorHit;
import com.cmcc.cmdevops.security.federation.vector.VectorSearchClient;

@Service
public class QueryService {

    private final FederatedSqlAnalyzer analyzer;
    private final QueryRewriter rewriter;
    private final QueryRouter router;
    private final QueryExecutor executor;
    private final ResultCache resultCache;
    private final CatalogRegistry catalogRegistry;
    private final VectorSearchClient vectorSearchClient;
    private final QuotaService quotaService;
    private final AuditService auditService;

    public QueryService(
            FederatedSqlAnalyzer analyzer,
            QueryRewriter rewriter,
            QueryRouter router,
            QueryExecutor executor,
            ResultCache resultCache,
            CatalogRegistry catalogRegistry,
            VectorSearchClient vectorSearchClient,
            QuotaService quotaService,
            AuditService auditService) {
        this.analyzer = analyzer;
        this.rewriter = rewriter;
        this.router = router;
        this.executor = executor;
        this.resultCache = resultCache;
        this.catalogRegistry = catalogRegistry;
        this.vectorSearchClient = vectorSearchClient;
        this.quotaService = quotaService;
        this.auditService = auditService;
    }

    public QueryResponse execute(QueryRequest request) {
        long started = System.currentTimeMillis();
        String user = GatewayUser.current().name();
        quotaService.check(user);

        QueryResponse cached = resultCache.get(request);
        if (cached != null) {
            QueryResponse response = withCached(cached, System.currentTimeMillis() - started);
            auditService.log(user, request.getSql(), null, response);
            return response;
        }

        if (rewriter.isGatewayCatalogCommand(request.getSql())) {
            RoutePlan plan = new RoutePlan(EngineType.GATEWAY, null, java.util.Set.of(),
                    "网关本地 SHOW CATALOGS", "SHOW CATALOGS");
            QueryResponse response = showCatalogs(plan, System.currentTimeMillis() - started);
            auditService.log(user, request.getSql(), plan, response);
            return response;
        }

        String mappedSql = rewriter.applyKafkaMappings(request.getSql());
        AnalyzedQuery analyzed = analyzer.analyze(mappedSql);
        String afterVector = injectVectors(analyzed, request.getParameters());
        AnalyzedQuery routedQuery = afterVector.equals(analyzed.normalizedSql())
                ? analyzed
                : analyzer.analyze(afterVector);

        RoutePlan routed = router.route(routedQuery, afterVector, request.getEngine());
        String rewritten = rewriter.applyRouteRewrite(routed.rewrittenSql(), routed);
        RoutePlan plan = new RoutePlan(routed.engine(), routed.directCatalog(), routed.catalogs(),
                routed.reason(), rewritten);

        if (plan.engine() == EngineType.SPARK) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Spark 引擎尚未接入");
        }

        if (request.isExplain() && !analyzed.explain()) {
            QueryResponse response = explain(plan, System.currentTimeMillis() - started);
            auditService.log(user, request.getSql(), plan, response);
            return response;
        }

        String sql = analyzed.explain() ? "EXPLAIN " + plan.rewrittenSql() : plan.rewrittenSql();
        RoutePlan execPlan = new RoutePlan(plan.engine(), plan.directCatalog(), plan.catalogs(),
                plan.reason(), sql);
        QueryResponse.ResultSetData data = executor.execute(execPlan);
        QueryResponse response = new QueryResponse(
                true,
                plan.engine().name(),
                plan.reason(),
                plan.catalogList(),
                plan.rewrittenSql(),
                data.columns(),
                data.rows(),
                data.rows().size(),
                System.currentTimeMillis() - started,
                false,
                null);
        resultCache.put(request, response);
        auditService.log(user, request.getSql(), plan, response);
        return response;
    }

    public List<CatalogView> listCatalogs() {
        return catalogRegistry.list().stream().map(CatalogView::from).toList();
    }

    private String injectVectors(AnalyzedQuery analyzed, Map<String, Object> parameters) {
        if (analyzed.vectorSearches().isEmpty()) {
            return analyzed.normalizedSql();
        }
        Map<String, List<VectorHit>> hitsByPlaceholder = new LinkedHashMap<>();
        for (VectorSearchCall call : analyzed.vectorSearches()) {
            List<Double> vector = call.resolveVector(parameters == null ? Map.of() : parameters);
            List<VectorHit> hits = vectorSearchClient.search(
                    call.collection(), call.field(), vector, call.k(), call.filter());
            hitsByPlaceholder.put(call.placeholder(), hits);
        }
        return rewriter.injectVectorValues(analyzed, hitsByPlaceholder);
    }

    private QueryResponse showCatalogs(RoutePlan plan, long elapsedMs) {
        List<QueryResponse.ColumnMeta> columns = List.of(new QueryResponse.ColumnMeta("Catalog", "varchar"));
        List<List<Object>> rows = catalogRegistry.list().stream()
                .map(record -> List.<Object>of(record.getName()))
                .toList();
        return new QueryResponse(true, EngineType.GATEWAY.name(), plan.reason(), List.of(),
                plan.rewrittenSql(), columns, rows, rows.size(), elapsedMs, false, null);
    }

    private QueryResponse explain(RoutePlan plan, long elapsedMs) {
        List<QueryResponse.ColumnMeta> columns = List.of(
                new QueryResponse.ColumnMeta("engine", "varchar"),
                new QueryResponse.ColumnMeta("reason", "varchar"),
                new QueryResponse.ColumnMeta("sql", "varchar"));
        List<List<Object>> rows = List.of(List.of(plan.engine().name(), plan.reason(), plan.rewrittenSql()));
        return new QueryResponse(true, plan.engine().name(), plan.reason(), plan.catalogList(),
                plan.rewrittenSql(), columns, rows, 1, elapsedMs, false, "网关路由计划（未下发引擎）");
    }

    private QueryResponse withCached(QueryResponse cached, long elapsedMs) {
        return new QueryResponse(
                cached.success(),
                cached.engine(),
                cached.routeReason(),
                cached.catalogs(),
                cached.rewrittenSql(),
                cached.columns(),
                cached.rows(),
                cached.rowCount(),
                elapsedMs,
                true,
                cached.message());
    }
}
