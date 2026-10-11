package com.cmcc.cmdevops.security.federation.engine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;

public class JdbcQueryEngine {

    public QueryResponse.ResultSetData execute(DataSource dataSource, String sql, int maxRows, int timeoutSeconds) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            boolean hasResult = statement.execute(sql);
            if (!hasResult) {
                return new QueryResponse.ResultSetData(List.of(), List.of());
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return read(resultSet, maxRows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    private QueryResponse.ResultSetData read(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<QueryResponse.ColumnMeta> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new QueryResponse.ColumnMeta(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < maxRows) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(resultSet.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResponse.ResultSetData(columns, rows);
    }
}
