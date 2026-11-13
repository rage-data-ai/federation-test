package com.cmcc.cmdevops.security.federation.store;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.llm.LlmSettingsStore;

@Component
public class JdbcLlmStore implements LlmSettingsStore {

    private static final int ROW_ID = 1;

    private final JdbcTemplate jdbc;

    public JdbcLlmStore(FederalDataSource dataSource) {
        this.jdbc = dataSource.jdbc();
    }

    @Override
    public Settings read() {
        List<Settings> rows = jdbc.query("""
                SELECT protocol, base_url, api_key, model FROM fed_llm WHERE id = ?
                """, (rs, row) -> new Settings(
                rs.getString("protocol"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("model")), ROW_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void write(Settings settings) {
        jdbc.update("""
                INSERT INTO fed_llm (id, protocol, base_url, api_key, model)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  protocol = VALUES(protocol), base_url = VALUES(base_url),
                  api_key = VALUES(api_key), model = VALUES(model)
                """,
                ROW_ID,
                settings.protocol(),
                settings.baseUrl(),
                settings.apiKey(),
                settings.model());
    }
}
