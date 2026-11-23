package com.cmcc.cmdevops.security.federation.store;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings.Settings;
import com.cmcc.cmdevops.security.federation.ask.embed.EmbedSettingsStore;
import com.cmcc.cmdevops.security.federation.security.SecretCrypto;

@Component
public class JdbcEmbedStore implements EmbedSettingsStore {

    private static final int ROW_ID = 1;

    private final JdbcTemplate jdbc;
    private final SecretCrypto secrets;

    public JdbcEmbedStore(FederalDataSource dataSource, SecretCrypto secrets) {
        this.jdbc = dataSource.jdbc();
        this.secrets = secrets;
    }

    @Override
    public Settings read() {
        List<Settings> rows = jdbc.query("""
                SELECT endpoint, api_key, model FROM fed_ask_embed WHERE id = ?
                """, (rs, row) -> new Settings(
                rs.getString("endpoint"),
                secrets.open(rs.getString("api_key")),
                rs.getString("model")), ROW_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void write(Settings settings) {
        jdbc.update("""
                INSERT INTO fed_ask_embed (id, endpoint, api_key, model)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  endpoint = VALUES(endpoint), api_key = VALUES(api_key), model = VALUES(model)
                """,
                ROW_ID,
                settings.endpoint(),
                secrets.seal(settings.apiKey()),
                settings.model());
    }
}
