package com.cmcc.cmdevops.security.federation.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

class SecretAtRestMigratorTest {

    @Test
    void skipsWhenCryptoDisabled() {
        SecretCrypto crypto = SecretCrypto.forTests("");
        FederalDataSource federal = mock(FederalDataSource.class);
        new SecretAtRestMigrator(crypto, federal).run(new DefaultApplicationArguments());
        verify(federal, never()).jdbc();
    }

    @Test
    void encryptsPlaintextRows() {
        SecretCrypto crypto = SecretCrypto.forTests("migrate-key");
        FederalDataSource federal = mock(FederalDataSource.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(federal.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(contains("fed_llm")))
                .thenReturn(List.of(Map.of("id", 1, "secret", "sk-plain")));
        when(jdbc.queryForList(contains("fed_ask_embed")))
                .thenReturn(List.of(Map.of("id", 1, "secret", SecretCrypto.PREFIX + "already")));
        when(jdbc.queryForList(contains("fed_catalog")))
                .thenReturn(List.of(Map.of("id", "mysql", "secret", "db-pass")));

        new SecretAtRestMigrator(crypto, federal).run(new DefaultApplicationArguments());

        verify(jdbc, atLeastOnce()).update(contains("fed_llm"), anyString(), eq(1));
        verify(jdbc, atLeastOnce()).update(contains("fed_catalog"), anyString(), eq("mysql"));
    }
}
