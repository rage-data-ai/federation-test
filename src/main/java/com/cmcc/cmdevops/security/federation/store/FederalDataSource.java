package com.cmcc.cmdevops.security.federation.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接 federal 库，并按业务建表：数据源、元数据表、元数据列、大模型配置。
 */
@Component
public class FederalDataSource {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public FederalDataSource(FederationProperties properties, ObjectMapper objectMapper) {
        FederationProperties.Repository repository = properties.getRepository();
        String password = repository.getPassword();
        if (password == null || password.isBlank()) {
            password = readPassword(Path.of("data/federal.password"));
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(repository.getUrl());
        pool.setUsername(repository.getUsername());
        pool.setPassword(password);
        pool.setPoolName("federal");
        pool.setMaximumPoolSize(4);
        pool.setConnectionTimeout(8_000);
        DataSource dataSource;
        try {
            dataSource = new HikariDataSource(pool);
            dataSource.getConnection().close();
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接 MySQL（" + repository.getUrl() + "）。原因: " + e.getMessage(), e);
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ensureSchema();
        migrateLegacy(objectMapper);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public TransactionTemplate transactions() {
        return transactions;
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fed_catalog (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  name VARCHAR(128) NOT NULL,
                  type VARCHAR(32) NOT NULL,
                  route VARCHAR(16) NOT NULL,
                  host VARCHAR(255) NOT NULL,
                  port INT NULL,
                  database_name VARCHAR(255) NULL,
                  username VARCHAR(255) NULL,
                  password VARCHAR(512) NULL,
                  jdbc_url VARCHAR(1024) NULL,
                  driver VARCHAR(255) NULL,
                  extra MEDIUMTEXT NULL,
                  tls TINYINT(1) NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_fed_catalog_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fed_meta_table (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  catalog_id BIGINT NOT NULL,
                  schema_name VARCHAR(255) NOT NULL,
                  table_name VARCHAR(255) NOT NULL,
                  collected_at VARCHAR(40) NULL,
                  UNIQUE KEY uk_fed_meta_table (catalog_id, schema_name, table_name),
                  CONSTRAINT fk_fed_meta_table_catalog FOREIGN KEY (catalog_id)
                    REFERENCES fed_catalog (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        linkMetaTablesByCatalogId();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fed_meta_column (
                  table_id BIGINT NOT NULL,
                  ordinal INT NOT NULL,
                  column_name VARCHAR(512) NOT NULL,
                  data_type TEXT NULL,
                  PRIMARY KEY (table_id, ordinal),
                  CONSTRAINT fk_fed_meta_column FOREIGN KEY (table_id)
                    REFERENCES fed_meta_table (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("ALTER TABLE fed_meta_column MODIFY column_name VARCHAR(512) NOT NULL");
        jdbc.execute("ALTER TABLE fed_meta_column MODIFY data_type TEXT NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fed_llm (
                  id TINYINT NOT NULL PRIMARY KEY,
                  protocol VARCHAR(32) NOT NULL,
                  base_url VARCHAR(512) NOT NULL,
                  api_key VARCHAR(512) NULL,
                  model VARCHAR(128) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void migrateLegacy(ObjectMapper objectMapper) {
        if (count("fed_catalog") == 0) {
            String catalogs = legacyBody("catalogs");
            if (catalogs != null && !catalogs.isBlank()) {
                Map<String, CatalogRecord> loaded = objectMapper.readValue(catalogs,
                        new TypeReference<LinkedHashMap<String, CatalogRecord>>() {
                        });
                loaded.forEach((name, record) -> {
                    record.setName(name);
                    new JdbcCatalogStore(this).upsert(record);
                });
            }
        }
        if (count("fed_meta_table") == 0) {
            String metadata = legacyBody("metadata");
            if (metadata != null && !metadata.isBlank()) {
                Map<String, TableSnapshot> loaded = objectMapper.readValue(metadata,
                        new TypeReference<LinkedHashMap<String, TableSnapshot>>() {
                        });
                new JdbcMetadataStore(this).replaceAll(List.copyOf(loaded.values()));
            }
        }
        if (count("fed_llm") == 0) {
            String llm = legacyBody("llm");
            if (llm != null && !llm.isBlank()) {
                new JdbcLlmStore(this).write(objectMapper.readValue(llm, Settings.class));
            }
        }
        if (tableExists("fed_document")) {
            jdbc.execute("DROP TABLE fed_document");
        }
    }

    /** 已有库把数据源名称改成自增 id，元数据表改为 catalog_id 外键。 */
    private void linkMetaTablesByCatalogId() {
        if (!columnExists("fed_meta_table", "catalog")) {
            return;
        }
        if (!columnExists("fed_catalog", "id")) {
            jdbc.execute("""
                    ALTER TABLE fed_catalog
                      ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT UNIQUE FIRST
                    """);
        }
        if (!primaryKeyIs("fed_catalog", "id")) {
            if (indexExists("fed_catalog", "uk_fed_catalog_name")) {
                jdbc.execute("""
                        ALTER TABLE fed_catalog
                          DROP PRIMARY KEY,
                          ADD PRIMARY KEY (id)
                        """);
            } else {
                jdbc.execute("""
                        ALTER TABLE fed_catalog
                          DROP PRIMARY KEY,
                          ADD PRIMARY KEY (id),
                          ADD UNIQUE KEY uk_fed_catalog_name (name)
                        """);
            }
        }
        for (String index : indexesOn("fed_catalog", "id")) {
            jdbc.execute("ALTER TABLE fed_catalog DROP INDEX `" + index + "`");
        }
        if (!columnExists("fed_meta_table", "catalog_id")) {
            jdbc.execute("ALTER TABLE fed_meta_table ADD COLUMN catalog_id BIGINT NULL");
        }
        jdbc.update("""
                UPDATE fed_meta_table t
                JOIN fed_catalog c ON c.name = t.catalog
                SET t.catalog_id = c.id
                WHERE t.catalog_id IS NULL
                """);
        Integer orphans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fed_meta_table WHERE catalog_id IS NULL", Integer.class);
        if (orphans != null && orphans > 0) {
            throw new IllegalStateException("有 " + orphans + " 张元数据表找不到对应的数据源，未改外键");
        }
        if (indexExists("fed_meta_table", "uk_fed_meta_table")) {
            jdbc.execute("ALTER TABLE fed_meta_table DROP INDEX uk_fed_meta_table");
        }
        jdbc.execute("ALTER TABLE fed_meta_table DROP COLUMN catalog");
        jdbc.execute("ALTER TABLE fed_meta_table MODIFY catalog_id BIGINT NOT NULL");
        jdbc.execute("""
                ALTER TABLE fed_meta_table
                  ADD UNIQUE KEY uk_fed_meta_table (catalog_id, schema_name, table_name),
                  ADD CONSTRAINT fk_fed_meta_table_catalog FOREIGN KEY (catalog_id)
                    REFERENCES fed_catalog (id) ON DELETE CASCADE
                """);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean primaryKeyIs(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = 'PRIMARY' AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private List<String> indexesOn(String table, String column) {
        return jdbc.query("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? AND index_name <> 'PRIMARY'
                """, (rs, row) -> rs.getString(1), table, column);
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, table, index);
        return count != null && count > 0;
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private String legacyBody(String name) {
        if (!tableExists("fed_document")) {
            return null;
        }
        List<String> rows = jdbc.query("SELECT body FROM fed_document WHERE name = ?",
                (rs, row) -> rs.getString(1), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String readPassword(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file).trim();
        } catch (Exception e) {
            return "";
        }
    }

}
