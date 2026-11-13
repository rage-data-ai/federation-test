package com.cmcc.cmdevops.security.federation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

class QueryExecutorTest {

    private DataSource trino;
    private CatalogDataSourceRegistry registry;
    private FederationProperties properties;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() {
        trino = mock(DataSource.class);
        registry = new CatalogDataSourceRegistry();
        properties = new FederationProperties();
        properties.setMaxRows(100);
        properties.getTrino().setQueryTimeoutSeconds(12);
        executor = new QueryExecutor(trino, registry, properties);
    }

    @Test
    void executeSqlUsesTrinoDataSource() throws Exception {
        stubSelect(trino, "SELECT 1", 7);
        QueryResponse.ResultSetData data = executor.execute("SELECT 1");
        assertEquals(1, data.rows().size());
        assertEquals(7, data.rows().get(0).get(0));
    }

    @Test
    void executeDirectPlanUsesCatalogDataSource() throws Exception {
        DataSource direct = mock(DataSource.class);
        registry.put("mysql_shop", direct);
        stubSelect(direct, "SELECT id FROM shop.t", "a");

        RoutePlan plan = new RoutePlan(EngineType.DIRECT, "mysql_shop", Set.of("mysql_shop"),
                "single direct", "SELECT id FROM shop.t");
        QueryResponse.ResultSetData data = executor.execute(plan);
        assertEquals("a", data.rows().get(0).get(0));
    }

    @Test
    void executeDirectMissingCatalogFails() {
        RoutePlan plan = new RoutePlan(EngineType.DIRECT, "missing", Set.of("missing"), "x", "SELECT 1");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> executor.execute(plan));
        assertTrue(error.getMessage().contains("直连数据源未就绪"));
    }

    @Test
    void sparkPlanNotImplemented() {
        RoutePlan plan = new RoutePlan(EngineType.SPARK, null, Set.of(), "spark", "SELECT 1");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> executor.execute(plan));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, error.getStatusCode());
    }

    @Test
    void trinoPlanDelegatesToDefaultExecute() throws Exception {
        stubSelect(trino, "SELECT 2", 2);
        RoutePlan plan = new RoutePlan(EngineType.TRINO, null, Set.of("a", "b"), "federated", "SELECT 2");
        assertEquals(2, executor.execute(plan).rows().get(0).get(0));
    }

    private static void stubSelect(DataSource dataSource, String sql, Object value) throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(sql)).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("v");
        when(meta.getColumnTypeName(1)).thenReturn("varchar");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(value);
    }
}
