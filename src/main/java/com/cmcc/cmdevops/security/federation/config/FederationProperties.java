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
    private final Llm llm = new Llm();
    private final Ask ask = new Ask();
    private final Cors cors = new Cors();
    private Map<String, Catalog> catalogs = new LinkedHashMap<>();
    private Map<String, String> kafkaMappings = new LinkedHashMap<>();
    private int maxRows = 10_000;
    private String ontologyStore = "data/tdb2";
    private final Repository repository = new Repository();

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

    public Llm getLlm() {
        return llm;
    }

    public Ask getAsk() {
        return ask;
    }

    public Cors getCors() {
        return cors;
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

    public String getOntologyStore() {
        return ontologyStore;
    }

    public void setOntologyStore(String ontologyStore) {
        this.ontologyStore = ontologyStore;
    }

    public Repository getRepository() {
        return repository;
    }

    /** 网关自己的配置库，替代 data/*.json。须通过 FEDERAL_DB_URL 等环境变量或配置文件提供。 */
    public static class Repository {
        private String url = "";
        private String username = "";
        private String password = "";

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
        /** 用户名 → Bearer token（兼容旧配置；未单独配 roles 时默认 QUERY+ADMIN） */
        private Map<String, String> tokens = new LinkedHashMap<>();
        /** 用户名 → 逗号分隔角色，如 QUERY 或 QUERY,ADMIN */
        private Map<String, String> roles = new LinkedHashMap<>();
        /**
         * 用户名 → 可访问 catalog（逗号分隔或 {@code *}）。非空时启用资源 ACL；
         * 未列出的用户无任何 catalog；ADMIN 角色绕过。
         */
        private Map<String, String> catalogs = new LinkedHashMap<>();
        private final Jwt jwt = new Jwt();

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

        public Map<String, String> getRoles() {
            return roles;
        }

        public void setRoles(Map<String, String> roles) {
            this.roles = roles == null ? new LinkedHashMap<>() : roles;
        }

        public Map<String, String> getCatalogs() {
            return catalogs;
        }

        public void setCatalogs(Map<String, String> catalogs) {
            this.catalogs = catalogs == null ? new LinkedHashMap<>() : catalogs;
        }

        public Jwt getJwt() {
            return jwt;
        }
    }

    /** 可选：校验外部 IdP 签发的 JWT（issuer-uri 非空时启用）。 */
    public static class Jwt {
        private String issuerUri = "";
        private String rolesClaim = "roles";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri == null ? "" : issuerUri;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim = rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim;
        }

        public boolean isEnabled() {
            return !issuerUri.isBlank();
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

    public static class Llm {
        /** openai 或 anthropic */
        private String protocol = "openai";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private double temperature = 0.0;
        private int maxTokens = 8192;
        private int timeoutSeconds = 180;

        public boolean isConfigured() {
            return !baseUrl.isBlank() && !model.isBlank();
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null ? "" : model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173,http://localhost:18080";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? "" : allowedOrigins;
        }
    }

    public static class Ask {
        /** get_business_context 置信度低于该值时升级到多轮工具检索 */
        private double minConfidence = 0.5;
        private int maxPathDepth = 4;
        private int sampleRows = 3;
        private int resultPreviewRows = 50;

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public int getMaxPathDepth() {
            return maxPathDepth;
        }

        public void setMaxPathDepth(int maxPathDepth) {
            this.maxPathDepth = maxPathDepth;
        }

        public int getSampleRows() {
            return sampleRows;
        }

        public void setSampleRows(int sampleRows) {
            this.sampleRows = sampleRows;
        }

        public int getResultPreviewRows() {
            return resultPreviewRows;
        }

        public void setResultPreviewRows(int resultPreviewRows) {
            this.resultPreviewRows = resultPreviewRows;
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
