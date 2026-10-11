package com.cmcc.cmdevops.security.federation.engine;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

@Component
public class QueryExecutor {

    private final DataSource trinoDataSource;
    private final FederationProperties properties;
    private final JdbcQueryEngine jdbcQueryEngine = new JdbcQueryEngine();

    public QueryExecutor(
            @Qualifier("trinoDataSource") DataSource trinoDataSource,
            FederationProperties properties) {
        this.trinoDataSource = trinoDataSource;
        this.properties = properties;
    }

    public QueryResponse.ResultSetData execute(String sql) {
        return jdbcQueryEngine.execute(
                trinoDataSource,
                sql,
                properties.getMaxRows(),
                properties.getTrino().getQueryTimeoutSeconds());
    }

    public QueryResponse.ResultSetData execute(RoutePlan plan) {
        return execute(plan.rewrittenSql());
    }
}
