package com.cmcc.cmdevops.security.federation.store;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogStore;

@Component
public class JdbcCatalogStore implements CatalogStore {

    private final JdbcTemplate jdbc;

    public JdbcCatalogStore(FederalDataSource dataSource) {
        this.jdbc = dataSource.jdbc();
    }

    @Override
    public List<CatalogRecord> list() {
        return jdbc.query("""
                SELECT name, type, route, host, port, database_name, username, password,
                       jdbc_url, driver, extra, tls
                FROM fed_catalog
                ORDER BY name
                """, (rs, row) -> {
            CatalogRecord record = new CatalogRecord();
            record.setName(rs.getString("name"));
            record.setType(rs.getString("type"));
            record.setRoute(rs.getString("route"));
            record.setHost(rs.getString("host"));
            int port = rs.getInt("port");
            record.setPort(rs.wasNull() ? null : port);
            record.setDatabase(rs.getString("database_name"));
            record.setUsername(rs.getString("username"));
            record.setPassword(rs.getString("password"));
            record.setUrl(rs.getString("jdbc_url"));
            record.setDriver(rs.getString("driver"));
            record.setExtra(rs.getString("extra"));
            record.setTls(rs.getInt("tls") != 0);
            return record;
        });
    }

    @Override
    public void upsert(CatalogRecord record) {
        jdbc.update("""
                INSERT INTO fed_catalog
                  (name, type, route, host, port, database_name, username, password, jdbc_url, driver, extra, tls)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  type = VALUES(type), route = VALUES(route), host = VALUES(host), port = VALUES(port),
                  database_name = VALUES(database_name), username = VALUES(username), password = VALUES(password),
                  jdbc_url = VALUES(jdbc_url), driver = VALUES(driver), extra = VALUES(extra), tls = VALUES(tls)
                """,
                record.getName(),
                record.getType(),
                record.getRoute() == null ? "TRINO" : record.getRoute(),
                record.getHost(),
                record.getPort(),
                record.getDatabase(),
                record.getUsername(),
                record.getPassword(),
                record.getUrl(),
                record.getDriver(),
                record.getExtra(),
                record.isTls() ? 1 : 0);
    }

    @Override
    public void delete(String name) {
        jdbc.update("DELETE FROM fed_catalog WHERE name = ?", name);
    }
}
