package com.cmcc.cmdevops.security.federation.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cmcc.cmdevops.security.federation.account.JdbcUserStore;
import com.cmcc.cmdevops.security.federation.account.UserAccount;
import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.account.UserService.Session;
import com.cmcc.cmdevops.security.federation.account.UserService.UserPatch;
import com.cmcc.cmdevops.security.federation.account.UserSession;
import com.cmcc.cmdevops.security.federation.account.UserStore.DuplicateUsernameException;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class JdbcUserStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("federal")
            .withUsername("federation")
            .withPassword("federation");

    private static FederalDataSource dataSource;
    private static JdbcUserStore store;

    @BeforeAll
    static void openStore() {
        FederationProperties properties = new FederationProperties();
        properties.getRepository().setUrl(MYSQL.getJdbcUrl()
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        properties.getRepository().setUsername(MYSQL.getUsername());
        properties.getRepository().setPassword(MYSQL.getPassword());
        dataSource = new FederalDataSource(properties, JsonMapper.builder().build());
        store = new JdbcUserStore(dataSource);
    }

    @BeforeEach
    void clean() {
        dataSource.jdbc().update("DELETE FROM fed_user");
    }

    private static UserAccount account(String username, Set<GatewayRole> roles) {
        return new UserAccount(0, username, username, "hash-" + username, roles, UserAccount.ACTIVE, 1_000, 1_000, 0);
    }

    @Test
    void accountsRoundTripAndRejectDuplicates() {
        UserAccount alice = store.insert(account("alice", EnumSet.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        UserAccount bob = store.insert(account("bob", Set.of(GatewayRole.QUERY)));

        assertTrue(alice.id() > 0);
        assertEquals(alice, store.find(alice.id()).orElseThrow());
        assertEquals(bob, store.findByUsername("bob").orElseThrow());
        assertTrue(store.findByUsername("carol").isEmpty());
        assertEquals(2, store.list().size());
        assertThrows(DuplicateUsernameException.class,
                () -> store.insert(account("alice", Set.of(GatewayRole.QUERY))));

        UserAccount changed = bob.withProfile("鲍勃", EnumSet.of(GatewayRole.ADMIN, GatewayRole.QUERY),
                        UserAccount.DISABLED, 2_000)
                .withPasswordHash("hash-2", 2_000)
                .withLastLoginAt(3_000);
        store.update(changed);
        assertEquals(changed, store.find(bob.id()).orElseThrow());
    }

    @Test
    void sessionsExtendExpireAndFollowTheUser() {
        UserAccount alice = store.insert(account("alice", Set.of(GatewayRole.QUERY)));
        UserAccount bob = store.insert(account("bob", Set.of(GatewayRole.QUERY)));
        store.saveSession(new UserSession("a".repeat(64), alice.id(), 1_000, 5_000));
        store.saveSession(new UserSession("b".repeat(64), alice.id(), 1_000, 9_000));
        store.saveSession(new UserSession("c".repeat(64), bob.id(), 1_000, 9_000));

        assertEquals(5_000, store.findSession("a".repeat(64)).orElseThrow().expiresAt());
        store.extendSession("a".repeat(64), 7_000);
        assertEquals(7_000, store.findSession("a".repeat(64)).orElseThrow().expiresAt());

        store.deleteExpiredSessions(7_000);
        assertTrue(store.findSession("a".repeat(64)).isEmpty());
        assertTrue(store.findSession("b".repeat(64)).isPresent());

        store.saveSession(new UserSession("d".repeat(64), alice.id(), 1_000, 9_000));
        store.deleteSessionsOf(alice.id(), "d".repeat(64));
        assertTrue(store.findSession("b".repeat(64)).isEmpty());
        assertTrue(store.findSession("d".repeat(64)).isPresent());
        store.deleteSessionsOf(alice.id(), null);
        assertTrue(store.findSession("d".repeat(64)).isEmpty());

        store.deleteSession("c".repeat(64));
        assertTrue(store.findSession("c".repeat(64)).isEmpty());
    }

    @Test
    void serviceFlowAgainstMysql() {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setRegistrationOpen(true);
        properties.getSecurity().setAdminUsername("root");
        properties.getSecurity().setAdminPassword("root-pass-11");
        UserService service = new UserService(
                store, properties, new BCryptPasswordEncoder(4), System::currentTimeMillis);
        service.ensureAdmin();
        service.ensureAdmin();

        Session alice = service.register("alice", "alice-pass-1", "爱丽丝");
        assertEquals("alice", service.authenticate(alice.token()).name());
        assertEquals("爱丽丝", service.find("alice").orElseThrow().displayName());
        assertTrue(service.login("root", "root-pass-11").user().roles().contains("ADMIN"));

        service.update(alice.user().id(), new UserPatch(null, null, UserAccount.DISABLED), "root");
        assertNull(service.authenticate(alice.token()));
        service.update(alice.user().id(), new UserPatch(null, null, UserAccount.ACTIVE), "root");
        assertNotNull(service.login("alice", "alice-pass-1"));
        assertEquals(2, service.list().size());
    }
}
