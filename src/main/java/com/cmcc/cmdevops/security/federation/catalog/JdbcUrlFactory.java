package com.cmcc.cmdevops.security.federation.catalog;

public final class JdbcUrlFactory {

    private JdbcUrlFactory() {
    }

    public static String build(CatalogRecord record) {
        if (record.getUrl() != null && !record.getUrl().isBlank()) {
            return record.getUrl();
        }
        String type = record.getType() == null ? "" : record.getType().toLowerCase();
        String host = record.getHost() == null || record.getHost().isBlank() ? "127.0.0.1" : record.getHost();
        int port = record.getPort() == null ? defaultPort(type) : record.getPort();
        String database = record.getDatabase() == null ? "" : record.getDatabase();
        return switch (type) {
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
            case "doris" -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            case "postgresql", "postgres" -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case "clickhouse" -> "jdbc:clickhouse://" + host + ":" + port + "/" + database;
            case "trino" -> "jdbc:trino://" + host + ":" + port;
            default -> throw new IllegalArgumentException("不支持的数据源类型: " + record.getType());
        };
    }

    public static int defaultPort(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "postgresql", "postgres" -> 5432;
            case "clickhouse" -> 8123;
            case "doris" -> 9030;
            case "trino" -> 8080;
            case "elasticsearch", "es" -> 9200;
            case "kafka" -> 9092;
            default -> 3306;
        };
    }
}
