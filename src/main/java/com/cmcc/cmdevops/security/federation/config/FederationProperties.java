package com.cmcc.cmdevops.security.federation.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "federation")
public class FederationProperties {

    private final Trino trino = new Trino();
    private final Security security = new Security();
    private final Quota quota = new Quota();
    private final Cache cache = new Cache();
    private final Vector vector = new Vector();
    private Map<String, Catalog> catalogs = new LinkedHashMap<>();
    private Map<String, String> kafkaMappings = new LinkedHashMap<>();
    private int maxRows = 10_000;
    private String catalogStore = "data/catalogs.json";

    public Trino getTrino() {
        return trino;
    }

    public Security getSecurity() {
        return security;
    }

    public Quota getQuota() {
        return quota;
    }

    public Cache getCache() {
        return cache;
    }

    public Vector getVector() {
        return vector;
    }

    public Map<String, Catalog> getCatalogs() {
        return catalogs;
    }

    public void setCatalogs(Map<String, Catalog> catalogs) {
        this.catalogs = catalogs;
    }

    public Map<String, String> getKafkaMappings() {
        return kafkaMappings;
    }

    public void setKafkaMappings(Map<String, String> kafkaMappings) {
        this.kafkaMappings = kafkaMappings;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public String getCatalogStore() {
        return catalogStore;
    }

    public void setCatalogStore(String catalogStore) {
        this.catalogStore = catalogStore;
    }

    public static class Trino {
        private String url = "jdbc:trino://localhost:8081";
        private String user = "federation";
        private String password = "";
        private int queryTimeoutSeconds = 120;
        private String catalogDir = "docker/trino/catalog";
        private String kafkaConfigDir = "docker/trino/kafka";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        public String getCatalogDir() {
            return catalogDir;
        }

        public void setCatalogDir(String catalogDir) {
            this.catalogDir = catalogDir;
        }

        public String getKafkaConfigDir() {
            return kafkaConfigDir;
        }

        public void setKafkaConfigDir(String kafkaConfigDir) {
            this.kafkaConfigDir = kafkaConfigDir;
        }
    }

    public static class Security {
        private boolean enabled = false;
        private Map<String, String> tokens = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getTokens() {
            return tokens;
        }

        public void setTokens(Map<String, String> tokens) {
            this.tokens = tokens;
        }
    }

    public static class Quota {
        private int maxRequestsPerMinute = 120;

        public int getMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }
    }

    public static class Cache {
        private boolean enabled = true;
        private int ttlSeconds = 30;
        private int maxSize = 256;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }

    public static class Vector {
        private int defaultK = 10;
        private String milvusUri = "";

        public int getDefaultK() {
            return defaultK;
        }

        public void setDefaultK(int defaultK) {
            this.defaultK = defaultK;
        }

        public String getMilvusUri() {
            return milvusUri;
        }

        public void setMilvusUri(String milvusUri) {
            this.milvusUri = milvusUri;
        }
    }

    public static class Catalog {
        private String type = "trino";
        private String route = "TRINO";
        private String url;
        private String username;
        private String password;
        private String driver;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getRoute() {
            return route;
        }

        public void setRoute(String route) {
            this.route = route;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = driver;
        }

        public boolean isDirect() {
            return "DIRECT".equalsIgnoreCase(route);
        }

        public String resolveDriver() {
            if (driver != null && !driver.isBlank()) {
                return driver;
            }
            return switch (type.toLowerCase()) {
                case "mysql", "doris" -> "com.mysql.cj.jdbc.Driver";
                case "postgresql", "postgres" -> "org.postgresql.Driver";
                case "clickhouse" -> "com.clickhouse.jdbc.ClickHouseDriver";
                case "trino" -> "io.trino.jdbc.TrinoDriver";
                default -> null;
            };
        }
    }
}
