package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.security.SecretCrypto;

class JdbcSecretStoreTest {

    private FederalDataSource dataSource;
    private JdbcTemplate jdbc;
    private SecretCrypto secrets;

    @BeforeEach
    void setUp() {
        dataSource = mock(FederalDataSource.class);
        jdbc = mock(JdbcTemplate.class);
        when(dataSource.jdbc()).thenReturn(jdbc);
        secrets = SecretCrypto.forTests("store-unit-key");
    }

    @Test
    void llmStoreSealsAndOpensApiKey() {
        JdbcLlmStore store = new JdbcLlmStore(dataSource, secrets);
        AtomicReference<String> sealed = new AtomicReference<>();
        when(jdbc.update(contains("fed_llm"), any(), any(), any(), argThat(value -> {
            if (value instanceof String text && text.startsWith(SecretCrypto.PREFIX)) {
                sealed.set(text);
                return true;
            }
            return false;
        }), any())).thenReturn(1);

        store.write(new Settings("openai", "http://x", "sk-live", "m1"));
        assertTrue(sealed.get() != null && sealed.get().startsWith(SecretCrypto.PREFIX));
        assertEquals("sk-live", secrets.open(sealed.get()));

        when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("protocol")).thenReturn("openai");
                    when(rs.getString("base_url")).thenReturn("http://x");
                    when(rs.getString("api_key")).thenReturn(sealed.get());
                    when(rs.getString("model")).thenReturn("m1");
                    return List.of(mapper.mapRow(rs, 0));
                });
        assertEquals("sk-live", store.read().apiKey());
    }

    @Test
    void embedStoreSealsApiKey() {
        JdbcEmbedStore store = new JdbcEmbedStore(dataSource, secrets);
        AtomicReference<String> sealed = new AtomicReference<>();
        when(jdbc.update(contains("fed_ask_embed"), any(), any(), argThat(value -> {
            if (value instanceof String text && text.startsWith(SecretCrypto.PREFIX)) {
                sealed.set(text);
                return true;
            }
            return false;
        }), any())).thenReturn(1);
        store.write(new AskEmbedSettings.Settings("http://e", "ek", "emb"));
        assertTrue(sealed.get() != null);

        when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    var rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("endpoint")).thenReturn("http://e");
                    when(rs.getString("api_key")).thenReturn(sealed.get());
                    when(rs.getString("model")).thenReturn("emb");
                    return List.of(mapper.mapRow(rs, 0));
                });
        assertEquals("ek", store.read().apiKey());
    }

    @Test
    void catalogStoreSealsPassword() {
        JdbcCatalogStore store = new JdbcCatalogStore(dataSource, secrets);
        AtomicReference<String> sealed = new AtomicReference<>();
        when(jdbc.update(contains("fed_catalog"), any(), any(), any(), any(), any(), any(), any(),
                argThat(value -> {
                    if (value instanceof String text && text.startsWith(SecretCrypto.PREFIX)) {
                        sealed.set(text);
                        return true;
                    }
                    return false;
                }), any(), any(), any(), anyInt())).thenReturn(1);

        CatalogRecord record = new CatalogRecord();
        record.setName("mysql");
        record.setType("mysql");
        record.setPassword("p@ss");
        store.upsert(record);
        assertTrue(sealed.get() != null);

        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getString("name")).thenReturn("mysql");
            when(rs.getString("type")).thenReturn("mysql");
            when(rs.getString("route")).thenReturn("DIRECT");
            when(rs.getString("host")).thenReturn(null);
            when(rs.getInt("port")).thenReturn(0);
            when(rs.wasNull()).thenReturn(true);
            when(rs.getString("database_name")).thenReturn(null);
            when(rs.getString("username")).thenReturn(null);
            when(rs.getString("password")).thenReturn(sealed.get());
            when(rs.getString("jdbc_url")).thenReturn(null);
            when(rs.getString("driver")).thenReturn(null);
            when(rs.getString("extra")).thenReturn(null);
            when(rs.getInt("tls")).thenReturn(0);
            return List.of(mapper.mapRow(rs, 0));
        });
        assertEquals("p@ss", store.list().get(0).getPassword());
        store.delete("mysql");
        verify(jdbc).update(contains("DELETE FROM fed_catalog"), eq("mysql"));
    }
}
