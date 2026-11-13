package com.cmcc.cmdevops.security.federation.engine;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

@Component
public class QueryExecutor {

    private final DataSource trinoDataSource;
    private final CatalogDataSourceRegistry catalogDataSources;
    private final FederationProperties properties;
    private final JdbcQueryEngine jdbcQueryEngine = new JdbcQueryEngine();

    public QueryExecutor(
            @Qualifier("trinoDataSource") DataSource trinoDataSource,
            CatalogDataSourceRegistry catalogDataSources,
            FederationProperties properties) {
        this.trinoDataSource = trinoDataSource;
        this.catalogDataSources = catalogDataSources;
        this.properties = properties;
    }

    public QueryResponse.ResultSetData execute(String sql) {
        return execute(sql, properties.getMaxRows());
    }

    public QueryResponse.ResultSetData execute(String sql, int maxRows) {
        return execute(sql, maxRows, properties.getTrino().getQueryTimeoutSeconds());
    }

    public QueryResponse.ResultSetData execute(String sql, int maxRows, int timeoutSeconds) {
        return jdbcQueryEngine.execute(trinoDataSource, sql, maxRows, timeoutSeconds);
    }

    public QueryResponse.ResultSetData execute(RoutePlan plan) {
        if (plan.engine() == EngineType.SPARK) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Spark 引擎尚未接入");
        }
        if (plan.engine() == EngineType.DIRECT) {
            DataSource dataSource = catalogDataSources.get(plan.directCatalog());
            if (dataSource == null) {
                throw new IllegalStateException("直连数据源未就绪: " + plan.directCatalog());
            }
            return jdbcQueryEngine.execute(
                    dataSource,
                    plan.rewrittenSql(),
                    properties.getMaxRows(),
                    properties.getTrino().getQueryTimeoutSeconds());
        }
        return execute(plan.rewrittenSql());
    }
}
