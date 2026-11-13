package com.cmcc.cmdevops.security.federation.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.JdbcUrlFactory;
import com.zaxxer.hikari.HikariDataSource;

@Component
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

    public void upsert(CatalogRecord record) {
        if (record == null || record.getName() == null) {
            return;
        }
        String driver = record.resolveDriver();
        if (driver == null) {
            remove(record.getName());
            return;
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("catalog-" + record.getName());
        dataSource.setJdbcUrl(JdbcUrlFactory.build(record));
        dataSource.setUsername(record.getUsername());
        if (record.getPassword() != null && !record.getPassword().isBlank()) {
            dataSource.setPassword(record.getPassword());
        }
        dataSource.setDriverClassName(driver);
        dataSource.setMaximumPoolSize(4);
        dataSource.setInitializationFailTimeout(-1);
        put(record.getName(), dataSource);
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
