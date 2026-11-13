package com.cmcc.cmdevops.security.federation.metadata;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.KafkaTopicLister;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;

@Service
public class MetadataService {

    private static final Set<String> SKIP_SCHEMAS = Set.of(
            "information_schema", "sys", "system", "performance_schema", "mysql");

    private final MetadataStore store;
    private final CatalogRegistry catalogRegistry;
    private final QueryExecutor queryExecutor;
    private final KafkaTopicLister kafkaTopicLister;

    public MetadataService(
            MetadataStore store,
            CatalogRegistry catalogRegistry,
            QueryExecutor queryExecutor,
            KafkaTopicLister kafkaTopicLister) {
        this.store = store;
        this.catalogRegistry = catalogRegistry;
        this.queryExecutor = queryExecutor;
        this.kafkaTopicLister = kafkaTopicLister;
    }

    public List<TableSnapshot> list() {
        return store.list();
    }

    public boolean delete(String catalog, String schema, String table) {
        if (isBlank(catalog) || isBlank(schema) || isBlank(table)) {
            throw new IllegalArgumentException("请指定 catalog、schema 和 table");
        }
        return store.deleteTable(catalog, schema, table);
    }

    public List<TableSnapshot> collect(CollectRequest request) {
        if (request.getCatalog() == null || request.getCatalog().isBlank()) {
            throw new IllegalArgumentException("请指定 catalog");
        }
        CatalogRecord record = catalogRegistry.get(request.getCatalog());
        if (record == null) {
            throw new IllegalArgumentException("数据源不存在: " + request.getCatalog());
        }
        if (isKafka(record) && isBlank(request.getTable())) {
            refreshKafkaTopics(record);
        }
        List<TableSnapshot> snapshots = isClickhouse(record)
                ? collectClickhouse(record.getName(), request.getSchema(), request.getTable())
                : collectFromInformationSchema(record.getName(), request.getSchema(), request.getTable());
        if (request.getTable() != null && !request.getTable().isBlank()) {
            if (snapshots.isEmpty()) {
                throw new IllegalStateException("未采集到表: " + request.getCatalog() + "." + request.getSchema() + "." + request.getTable());
            }
            store.put(snapshots.get(0));
            return snapshots;
        }
        store.replaceCatalog(request.getCatalog(), snapshots);
        return snapshots;
    }

    private void refreshKafkaTopics(CatalogRecord record) {
        List<String> topics = kafkaTopicLister.list(record).stream()
                .filter(topic -> !skipKafkaTopic(topic))
                .toList();
        String extra = joinKafkaTopics(topics);
        if (extra.isBlank() || extra.equals(record.getExtra())) {
            return;
        }
        record.setExtra(extra);
        catalogRegistry.save(record, true);
    }

    private List<TableSnapshot> collectClickhouse(String catalog, String schema, String table) {
        QueryResponse.ResultSetData data = queryExecutor.execute(clickhouseColumnsSql(catalog, schema, table), 200_000);
        return groupRows(catalog, data, 0, 1, 2, 3);
    }

    private List<TableSnapshot> collectFromInformationSchema(String catalog, String schema, String table) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT table_schema, table_name, column_name, data_type FROM ")
                .append(quote(catalog))
                .append(".information_schema.columns");
        List<String> filters = new ArrayList<>();
        if (!isBlank(schema)) {
            filters.add("table_schema = '" + escapeLiteral(schema) + "'");
        }
        if (!isBlank(table)) {
            if (isBlank(schema)) {
                throw new IllegalArgumentException("重采单表时请指定 schema");
            }
            filters.add("table_name = '" + escapeLiteral(table) + "'");
        }
        if (!filters.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", filters));
        }
        QueryResponse.ResultSetData data = queryExecutor.execute(sql.toString(), 200_000);
        return groupRows(catalog, data, 0, 1, 2, 3);
    }

    private List<TableSnapshot> groupRows(
            String catalog,
            QueryResponse.ResultSetData data,
            int schemaIdx,
            int tableIdx,
            int columnIdx,
            int typeIdx) {
        LinkedHashMap<String, TableSnapshot> grouped = new LinkedHashMap<>();
        String collectedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (List<Object> row : data.rows()) {
            if (row.size() <= Math.max(schemaIdx, Math.max(tableIdx, columnIdx))) {
                continue;
            }
            String tableSchema = String.valueOf(row.get(schemaIdx));
            String tableName = String.valueOf(row.get(tableIdx));
            if (skipCollectedTable(tableSchema, tableName)) {
                continue;
            }
            String columnName = String.valueOf(row.get(columnIdx));
            String columnType = row.size() > typeIdx && row.get(typeIdx) != null ? String.valueOf(row.get(typeIdx)) : "";
            String key = catalog + "." + tableSchema + "." + tableName;
            TableSnapshot snapshot = grouped.computeIfAbsent(key, ignored -> {
                TableSnapshot created = new TableSnapshot();
                created.setCatalog(catalog);
                created.setSchema(tableSchema);
                created.setTable(tableName);
                created.setCollectedAt(collectedAt);
                return created;
            });
            snapshot.getColumns().add(new ColumnSnapshot(columnName, columnType));
        }
        return new ArrayList<>(grouped.values());
    }

    static String clickhouseColumnsSql(String catalog, String schema, String table) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT database, \"table\", name, type FROM ")
                .append(quote(catalog))
                .append(".system.columns WHERE database NOT IN ('system', 'information_schema')");
        if (!isBlank(schema)) {
            sql.append(" AND database = '").append(escapeLiteral(schema)).append("'");
        }
        if (!isBlank(table)) {
            if (isBlank(schema)) {
                throw new IllegalArgumentException("重采单表时请指定 schema");
            }
            sql.append(" AND \"table\" = '").append(escapeLiteral(table)).append("'");
        }
        return sql.toString();
    }

    static boolean skipCollectedTable(String schema, String table) {
        if (schema != null && SKIP_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return table != null && table.startsWith(".inner.");
    }

    static boolean skipKafkaTopic(String topic) {
        return topic == null || topic.startsWith("__");
    }

    static String joinKafkaTopics(List<String> topics) {
        return topics.stream()
                .filter(topic -> !skipKafkaTopic(topic))
                .sorted()
                .collect(Collectors.joining(","));
    }

    static String quote(String name) {
        if (name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isClickhouse(CatalogRecord record) {
        return record.getType() != null && record.getType().equalsIgnoreCase("clickhouse");
    }

    private static boolean isKafka(CatalogRecord record) {
        return record.getType() != null && record.getType().equalsIgnoreCase("kafka");
    }
}
