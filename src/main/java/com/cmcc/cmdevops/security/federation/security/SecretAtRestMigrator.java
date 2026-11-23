package com.cmcc.cmdevops.security.federation.security;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

/**
 * 启动时把历史明文敏感字段改为 {@link SecretCrypto} 密文（幂等）。
 */
@Component
@Order(50)
public class SecretAtRestMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretAtRestMigrator.class);

    private final SecretCrypto crypto;
    private final FederalDataSource federal;

    public SecretAtRestMigrator(SecretCrypto crypto, FederalDataSource federal) {
        this.crypto = crypto;
        this.federal = federal;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!crypto.enabled()) {
            log.info("未配置 FEDERATION_SECRETS_KEY，敏感字段保持明文落库（仅建议本地）");
            return;
        }
        JdbcTemplate jdbc = federal.jdbc();
        widen(jdbc);
        int llm = migrateColumn(jdbc, "fed_llm", "api_key", "id");
        int embed = migrateColumn(jdbc, "fed_ask_embed", "api_key", "id");
        int catalog = migrateColumn(jdbc, "fed_catalog", "password", "name");
        log.info("敏感字段落库加密迁移完成 llm={} embed={} catalog={}", llm, embed, catalog);
    }

    private void widen(JdbcTemplate jdbc) {
        try {
            jdbc.execute("ALTER TABLE fed_llm MODIFY api_key TEXT NULL");
        } catch (RuntimeException ignored) {
            // already wide or unsupported
        }
        try {
            jdbc.execute("ALTER TABLE fed_ask_embed MODIFY api_key TEXT NULL");
        } catch (RuntimeException ignored) {
            // already wide
        }
        try {
            jdbc.execute("ALTER TABLE fed_catalog MODIFY password TEXT NULL");
        } catch (RuntimeException ignored) {
            // already wide
        }
    }

    private int migrateColumn(JdbcTemplate jdbc, String table, String column, String idColumn) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + idColumn + " AS id, " + column + " AS secret FROM " + table
                        + " WHERE " + column + " IS NOT NULL AND " + column + " <> ''");
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String secret = row.get("secret") == null ? null : String.valueOf(row.get("secret"));
            if (secret == null || secret.startsWith(SecretCrypto.PREFIX)) {
                continue;
            }
            String sealed = crypto.seal(secret);
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE " + idColumn + " = ?", sealed, id);
            updated++;
        }
        return updated;
    }
}
