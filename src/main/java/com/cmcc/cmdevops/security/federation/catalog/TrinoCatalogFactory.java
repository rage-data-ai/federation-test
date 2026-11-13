package com.cmcc.cmdevops.security.federation.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TrinoCatalogFactory {

    private TrinoCatalogFactory() {
    }

    public static boolean needsTrinoFile(CatalogRecord record) {
        return record.getType() != null && !record.getType().isBlank();
    }

    public static String fileName(CatalogRecord record) {
        return record.getName().toLowerCase(Locale.ROOT) + ".properties";
    }

    public static String dropCatalogSql(String name) {
        return "DROP CATALOG IF EXISTS " + name;
    }

    public static String createCatalogSql(CatalogRecord record) {
        String connector = null;
        List<String> properties = new ArrayList<>();
        for (String line : render(record).split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if ("connector.name".equals(key)) {
                connector = value;
                continue;
            }
            properties.add("\"" + key + "\" = '" + value.replace("'", "''") + "'");
        }
        if (connector == null || connector.isBlank()) {
            throw new IllegalArgumentException("catalog 缺少 connector.name");
        }
        return "CREATE CATALOG " + record.getName() + " USING " + connector
                + "\nWITH (\n  " + String.join(",\n  ", properties) + "\n)";
    }

    public static String render(CatalogRecord record) {
        return switch (record.getType().toLowerCase(Locale.ROOT)) {
            case "elasticsearch", "es" -> renderElasticsearch(record);
            case "kafka" -> renderKafka(record);
            case "mysql", "doris" -> renderJdbc(record, "mysql");
            case "clickhouse" -> renderJdbc(record, "clickhouse");
            case "postgresql", "postgres" -> renderJdbc(record, "postgresql");
            default -> throw new IllegalArgumentException("不支持的数据源类型: " + record.getType());
        };
    }

    private static String renderJdbc(CatalogRecord record, String connector) {
        int port = record.getPort() == null ? JdbcUrlFactory.defaultPort(record.getType()) : record.getPort();
        String database = record.getDatabase() == null ? "" : record.getDatabase().trim();
        String url = record.getUrl() != null && !record.getUrl().isBlank()
                ? record.getUrl()
                : jdbcUrl(connector, record.getHost(), port, database);
        StringBuilder text = new StringBuilder();
        text.append("connector.name=").append(connector).append('\n');
        text.append("connection-url=").append(url).append('\n');
        text.append("connection-user=").append(nullToEmpty(record.getUsername())).append('\n');
        text.append("connection-password=").append(nullToEmpty(record.getPassword())).append('\n');
        if ("clickhouse".equals(connector)) {
            text.append("join-pushdown.enabled=true\n");
            text.append("clickhouse.map-string-as-varchar=true\n");
        }
        return text.toString();
    }

    private static String jdbcUrl(String connector, String host, int port, String database) {
        String safeHost = host == null || host.isBlank() ? "127.0.0.1" : host;
        return switch (connector) {
            case "postgresql" -> "jdbc:postgresql://" + safeHost + ":" + port + "/" + (database.isBlank() ? "postgres" : database);
            case "clickhouse" -> "jdbc:clickhouse://" + safeHost + ":" + port + (database.isBlank() ? "" : "/" + database);
            default -> "jdbc:mysql://" + safeHost + ":" + port + (database.isBlank() ? "" : "/" + database);
        };
    }

    private static String renderElasticsearch(CatalogRecord record) {
        int port = record.getPort() == null ? 9200 : record.getPort();
        String schema = blankToDefault(record.getDatabase(), "default");
        StringBuilder text = new StringBuilder();
        text.append("connector.name=elasticsearch\n");
        text.append("elasticsearch.host=").append(record.getHost()).append('\n');
        text.append("elasticsearch.port=").append(port).append('\n');
        text.append("elasticsearch.default-schema-name=").append(schema).append('\n');
        if (record.getUsername() != null && !record.getUsername().isBlank()) {
            text.append("elasticsearch.security=PASSWORD\n");
            text.append("elasticsearch.auth.user=").append(record.getUsername()).append('\n');
            text.append("elasticsearch.auth.password=").append(nullToEmpty(record.getPassword())).append('\n');
        }
        if (record.isTls()) {
            text.append("elasticsearch.tls.enabled=true\n");
            text.append("elasticsearch.tls.verify-hostnames=false\n");
        }
        text.append("elasticsearch.ignore-publish-address=true\n");
        return text.toString();
    }

    private static String renderKafka(CatalogRecord record) {
        int port = record.getPort() == null ? 9092 : record.getPort();
        String schema = blankToDefault(record.getDatabase(), "default");
        StringBuilder text = new StringBuilder();
        text.append("connector.name=kafka\n");
        text.append("kafka.nodes=").append(record.getHost()).append(':').append(port).append('\n');
        text.append("kafka.default-schema=").append(schema).append('\n');
        String tables = record.getExtra() == null || record.getExtra().isBlank()
                ? "probe"
                : kafkaTables(schema, record.getExtra());
        text.append("kafka.table-names=").append(tables).append('\n');
        text.append("kafka.hide-internal-columns=false\n");
        text.append("kafka.table-description-supplier=FILE\n");
        if (hasKafkaSasl(record)) {
            text.append("kafka.config.resources=").append(kafkaClientContainerPath(record)).append('\n');
        }
        return text.toString();
    }

    public static boolean hasKafkaSasl(CatalogRecord record) {
        return "kafka".equalsIgnoreCase(record.getType())
                && record.getUsername() != null
                && !record.getUsername().isBlank();
    }

    public static String kafkaClientFileName(CatalogRecord record) {
        return record.getName().toLowerCase(Locale.ROOT) + "-client.properties";
    }

    public static String kafkaClientContainerPath(CatalogRecord record) {
        return "/etc/trino/kafka/" + kafkaClientFileName(record);
    }

    public static String renderKafkaClientConfig(CatalogRecord record) {
        String jaas = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + record.getUsername()
                + "\" password=\""
                + nullToEmpty(record.getPassword())
                + "\";";
        return "security.protocol=SASL_PLAINTEXT\n"
                + "sasl.mechanism=PLAIN\n"
                + "sasl.jaas.config=" + jaas + '\n';
    }

    static String kafkaTables(String schema, String extra) {
        if (extra == null || extra.isBlank()) {
            return "";
        }
        return Arrays.stream(extra.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(topic -> topic.contains(".") ? topic : topic)
                .collect(Collectors.joining(","));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
