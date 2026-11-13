package com.cmcc.cmdevops.security.federation.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.MetadataRecordStore;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;

@Component
public class JdbcMetadataStore implements MetadataRecordStore {

    private final FederalDataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcMetadataStore(FederalDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = dataSource.jdbc();
    }

    @Override
    public List<TableSnapshot> list() {
        Map<Long, TableSnapshot> tables = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, c.name AS catalog, t.schema_name, t.table_name, t.collected_at
                FROM fed_meta_table t
                JOIN fed_catalog c ON c.id = t.catalog_id
                ORDER BY c.name, t.schema_name, t.table_name
                """, rs -> {
            TableSnapshot snapshot = new TableSnapshot();
            snapshot.setCatalog(rs.getString("catalog"));
            snapshot.setSchema(rs.getString("schema_name"));
            snapshot.setTable(rs.getString("table_name"));
            snapshot.setCollectedAt(rs.getString("collected_at"));
            snapshot.setColumns(new ArrayList<>());
            tables.put(rs.getLong("id"), snapshot);
        });
        if (!tables.isEmpty()) {
            jdbc.query("""
                    SELECT table_id, column_name, data_type
                    FROM fed_meta_column
                    ORDER BY table_id, ordinal
                    """, rs -> {
                TableSnapshot snapshot = tables.get(rs.getLong("table_id"));
                if (snapshot != null) {
                    snapshot.getColumns().add(new ColumnSnapshot(rs.getString("column_name"), rs.getString("data_type")));
                }
            });
        }
        return new ArrayList<>(tables.values());
    }

    @Override
    public void replaceCatalog(String catalog, List<TableSnapshot> snapshots) {
        dataSource.transactions().executeWithoutResult(status -> {
            deleteCatalogRows(catalog);
            for (TableSnapshot snapshot : snapshots) {
                insert(snapshot);
            }
        });
    }

    /** 迁移旧数据时整批写入。 */
    public void replaceAll(List<TableSnapshot> snapshots) {
        dataSource.transactions().executeWithoutResult(status -> {
            jdbc.update("DELETE FROM fed_meta_column");
            jdbc.update("DELETE FROM fed_meta_table");
            for (TableSnapshot snapshot : snapshots) {
                insert(snapshot);
            }
        });
    }

    @Override
    public void put(TableSnapshot snapshot) {
        dataSource.transactions().executeWithoutResult(status -> {
            deleteTableRows(snapshot.getCatalog(), snapshot.getSchema(), snapshot.getTable());
            insert(snapshot);
        });
    }

    @Override
    public void deleteCatalog(String catalog) {
        deleteCatalogRows(catalog);
    }

    @Override
    public boolean deleteTable(String catalog, String schema, String table) {
        return deleteTableRows(catalog, schema, table) > 0;
    }

    private void deleteCatalogRows(String catalog) {
        jdbc.update("""
                DELETE t FROM fed_meta_table t
                JOIN fed_catalog c ON c.id = t.catalog_id
                WHERE c.name = ?
                """, catalog);
    }

    private int deleteTableRows(String catalog, String schema, String table) {
        return jdbc.update("""
                DELETE t FROM fed_meta_table t
                JOIN fed_catalog c ON c.id = t.catalog_id
                WHERE c.name = ? AND t.schema_name = ? AND t.table_name = ?
                """, catalog, schema, table);
    }

    private void insert(TableSnapshot snapshot) {
        long catalogId = catalogId(snapshot.getCatalog());
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fed_meta_table (catalog_id, schema_name, table_name, collected_at)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, catalogId);
            statement.setString(2, snapshot.getSchema());
            statement.setString(3, snapshot.getTable());
            statement.setString(4, snapshot.getCollectedAt());
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("写入元数据表失败: " + snapshot.key());
        }
        List<ColumnSnapshot> columns = snapshot.getColumns() == null ? List.of() : snapshot.getColumns();
        if (columns.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ColumnSnapshot column = columns.get(i);
            batch.add(new Object[] {id.longValue(), i, column.getName(), column.getType()});
        }
        jdbc.batchUpdate("""
                INSERT INTO fed_meta_column (table_id, ordinal, column_name, data_type)
                VALUES (?, ?, ?, ?)
                """, batch);
    }

    private long catalogId(String name) {
        List<Long> ids = jdbc.query("SELECT id FROM fed_catalog WHERE name = ?",
                (rs, row) -> rs.getLong(1), name);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("数据源不存在: " + name);
        }
        return ids.get(0);
    }
}
