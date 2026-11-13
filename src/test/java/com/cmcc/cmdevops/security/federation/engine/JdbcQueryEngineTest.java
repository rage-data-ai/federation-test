package com.cmcc.cmdevops.security.federation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;

class JdbcQueryEngineTest {

    private final JdbcQueryEngine engine = new JdbcQueryEngine();

    @Test
    void readsRowsAndRespectsMaxRows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT n")).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("n");
        when(meta.getColumnTypeName(1)).thenReturn("bigint");
        when(resultSet.next()).thenReturn(true, true, true);
        when(resultSet.getObject(1)).thenReturn(1, 2, 3);

        QueryResponse.ResultSetData data = engine.execute(dataSource, "SELECT n", 2, 30);

        assertEquals(List.of(new QueryResponse.ColumnMeta("n", "bigint")), data.columns());
        assertEquals(2, data.rows().size());
        assertEquals(1, data.rows().get(0).get(0));
        assertEquals(2, data.rows().get(1).get(0));
        verify(statement).setQueryTimeout(30);
        verify(statement).setMaxRows(2);
    }

    @Test
    void updateWithoutResultSetReturnsEmpty() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("UPDATE t SET x=1")).thenReturn(false);

        QueryResponse.ResultSetData data = engine.execute(dataSource, "UPDATE t SET x=1", 10, 5);
        assertTrue(data.columns().isEmpty());
        assertTrue(data.rows().isEmpty());
    }

    @Test
    void wrapsSqlException() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.execute(dataSource, "SELECT 1", 10, 5));
        assertTrue(error.getMessage().contains("SQL 执行失败"));
    }
}
