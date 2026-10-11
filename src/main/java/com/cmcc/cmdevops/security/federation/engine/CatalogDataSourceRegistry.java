package com.cmcc.cmdevops.security.federation.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

public class CatalogDataSourceRegistry {

    private final Map<String, DataSource> sources = new ConcurrentHashMap<>();

    public DataSource get(String catalog) {
        if (catalog == null) {
            return null;
        }
        DataSource exact = sources.get(catalog);
        if (exact != null) {
            return exact;
        }
        return sources.entrySet().stream()
                .filter(entry -> catalog.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public void put(String catalog, DataSource dataSource) {
        DataSource previous = sources.put(catalog, dataSource);
        closeQuietly(previous);
    }

    public void remove(String catalog) {
        closeQuietly(sources.remove(catalog));
    }

    private void closeQuietly(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
