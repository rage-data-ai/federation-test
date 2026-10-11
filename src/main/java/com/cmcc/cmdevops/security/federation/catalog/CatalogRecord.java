package com.cmcc.cmdevops.security.federation.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class CatalogRecord {

    private String name;
    private String type = "mysql";
    private String route = "DIRECT";
    private String host;
    private Integer port;
    private String database;
    private String username;
    private String password;
    private String url;
    private String driver;
    private String extra;
    private boolean tls;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    @JsonIgnore
    public boolean isDirect() {
        return route == null || "DIRECT".equalsIgnoreCase(route);
    }

    @JsonIgnore
    public String resolveDriver() {
        if (driver != null && !driver.isBlank()) {
            return driver;
        }
        if (type == null) {
            return null;
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
