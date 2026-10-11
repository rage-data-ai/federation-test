package com.cmcc.cmdevops.security.federation.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.cache.ResultCache;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.CatalogView;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.router.QueryRouter;
import com.cmcc.cmdevops.security.federation.sql.FederatedSqlAnalyzer;

@Service
public class QueryService {

    private final FederatedSqlAnalyzer analyzer;
    private final QueryRouter router;
    private final QueryExecutor executor;
    private final ResultCache resultCache;
    private final CatalogRegistry catalogRegistry;

    public QueryService(
            FederatedSqlAnalyzer analyzer,
            QueryRouter router,
            QueryExecutor executor,
            ResultCache resultCache,
            CatalogRegistry catalogRegistry) {
        this.analyzer = analyzer;
        this.router = router;
        this.executor = executor;
        this.resultCache = resultCache;
        this.catalogRegistry = catalogRegistry;
    }

    public QueryResponse execute(QueryRequest request) {
        long started = System.currentTimeMillis();
        QueryResponse cached = resultCache.get(request);
        if (cached != null) {
            return withCached(cached, System.currentTimeMillis() - started);
        }
        AnalyzedQuery analyzed = analyzer.analyze(request.getSql());
        RoutePlan plan = router.route(analyzed, analyzed.normalizedSql());
        if (request.isExplain() && !analyzed.explain()) {
            return explain(plan, System.currentTimeMillis() - started);
        }
        String sql = analyzed.explain() ? "EXPLAIN " + plan.rewrittenSql() : plan.rewrittenSql();
        QueryResponse.ResultSetData data = executor.execute(sql);
        QueryResponse response = new QueryResponse(
                true,
                EngineType.TRINO.name(),
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
        return response;
    }

    public List<CatalogView> listCatalogs() {
        return catalogRegistry.list().stream().map(CatalogView::from).toList();
    }

    private QueryResponse explain(RoutePlan plan, long elapsedMs) {
        List<QueryResponse.ColumnMeta> columns = List.of(
                new QueryResponse.ColumnMeta("engine", "varchar"),
                new QueryResponse.ColumnMeta("reason", "varchar"),
                new QueryResponse.ColumnMeta("sql", "varchar"));
        List<List<Object>> rows = List.of(List.of(EngineType.TRINO.name(), plan.reason(), plan.rewrittenSql()));
        return new QueryResponse(true, EngineType.TRINO.name(), plan.reason(), plan.catalogList(),
                plan.rewrittenSql(), columns, rows, 1, elapsedMs, false, "将提交给 Trino");
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
