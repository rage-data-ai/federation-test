package com.cmcc.cmdevops.security.federation.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.account.UserService.Session;
import com.cmcc.cmdevops.security.federation.account.UserService.UserPatch;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class UserServiceTest {

    private static final long HOUR = 3_600_000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private FederationProperties properties;
    private MemoryUserStore store;
    private UserService service;

    @BeforeEach
    void setUp() {
        properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        store = new MemoryUserStore();
        service = new UserService(store, properties, new BCryptPasswordEncoder(4), now::get);
        // 普通用例先有管理员，自助注册才开放；专门测 setup 的用例会清库。
        service.bootstrapAdmin("root", "root-pass-11", "管理员");
    }

    @Test
    void registerCreatesActiveQueryUserAndLogsIn() {
        Session session = service.register(" Alice ", "alice-pass-1", "  爱丽丝 ");

        assertEquals("alice", session.user().username());
        assertEquals("爱丽丝", session.user().displayName());
        assertEquals(List.of("QUERY"), session.user().roles());
        assertEquals(UserAccount.ACTIVE, session.user().status());
        assertEquals(now.get() + 24 * HOUR, session.expiresAt());

        GatewayUser user = service.authenticate(session.token());
        assertEquals("alice", user.name());
        assertFalse(user.hasRole(GatewayRole.ADMIN));
        UserAccount stored = store.findByUsername("alice").orElseThrow();
        assertNotEquals("alice-pass-1", stored.passwordHash());
        assertTrue(store.findSession(session.token()).isEmpty(), "库里不存令牌原文");
    }

    @Test
    void registerRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> service.register("ab", "alice-pass-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.register("-alice", "alice-pass-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.register("alice", "short", null));
        assertThrows(IllegalArgumentException.class, () -> service.register("alice", "密".repeat(25), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("alice", "alice-pass-1", "x".repeat(65)));
        assertEquals(1, store.list().size(), "仅保留 BeforeEach 创建的管理员");
    }

    @Test
    void registerRejectsTakenAndReservedNames() {
        properties.getSecurity().getTokens().put("demo", "demo-token");
        properties.getSecurity().getCatalogs().put("Analyst", "mysql_shop");
        properties.getSecurity().getRowFilters().put("tenant", "a.b.c:x = 1");
        service.register("alice", "alice-pass-1", null);

        for (String name : List.of("alice", "ALICE", "anonymous", "demo", "analyst", "tenant", "root")) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.register(name, "another-pass-1", null), name);
            assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        }
        assertEquals(2, store.list().size());
    }

    @Test
    void registerAndLoginStillWorkWhenEnabledFlagIsFalse() {
        properties.getSecurity().setEnabled(false);
        assertNotNull(service.login("root", "root-pass-11"));
        assertNotNull(service.register("alice", "alice-pass-1", null));
    }

    @Test
    void bootstrapAdminCreatesFirstAdminAndBlocksRepeat() {
        store.clear();
        service = new UserService(store, properties, new BCryptPasswordEncoder(4), now::get);
        assertTrue(service.needsSetup());
        assertThrows(IllegalStateException.class, () -> service.register("alice", "alice-pass-1", null));
        assertThrows(IllegalStateException.class, () -> service.login("alice", "alice-pass-1"));

        Session session = service.bootstrapAdmin("Root", "root-pass-11", " 管理员 ");
        assertFalse(service.needsSetup());
        assertEquals("root", session.user().username());
        assertEquals("管理员", session.user().displayName());
        assertEquals(List.of("ADMIN", "QUERY"), session.user().roles());
        assertNotNull(service.authenticate(session.token()));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.bootstrapAdmin("other", "other-pass-1", null)).getStatusCode());
        assertNotNull(service.register("alice", "alice-pass-1", null));
    }

    @Test
    void loginChecksPasswordAndRecordsLastLogin() {
        service.register("alice", "alice-pass-1", null);
        now.addAndGet(5_000);

        Session session = service.login("Alice", "alice-pass-1");
        assertEquals(now.get(), session.user().lastLoginAt());
        assertNotNull(service.authenticate(session.token()));

        for (String[] attempt : new String[][] {
                {"alice", "wrong-pass-1"}, {"nobody", "alice-pass-1"}, {"alice", null}, {null, "x"}, {"a b", "x"}}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.login(attempt[0], attempt[1]));
            assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
            assertEquals("用户名或密码错误", e.getReason());
        }
    }

    @Test
    void loginLocksAfterRepeatedFailuresThenRecovers() {
        service.register("alice", "alice-pass-1", null);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                    () -> service.login("alice", "wrong-pass-1")).getStatusCode());
            now.addAndGet(1_000);
        }
        ResponseStatusException locked = assertThrows(ResponseStatusException.class,
                () -> service.login("alice", "alice-pass-1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, locked.getStatusCode());
        assertTrue(locked.getReason().contains("15 分钟"), locked.getReason());

        now.addAndGet(LoginThrottle.WINDOW_MS);
        assertNotNull(service.login("alice", "alice-pass-1"));
    }

    @Test
    void successfulLoginClearsEarlierFailures() {
        service.register("alice", "alice-pass-1", null);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            assertThrows(ResponseStatusException.class, () -> service.login("alice", "wrong-pass-1"));
        }
        service.login("alice", "alice-pass-1");
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            assertThrows(ResponseStatusException.class, () -> service.login("alice", "wrong-pass-1"));
        }
        assertNotNull(service.login("alice", "alice-pass-1"));
    }

    @Test
    void sessionExpiresAfterIdleDayAndRenewsWhileUsed() {
        String token = service.register("alice", "alice-pass-1", null).token();

        now.addAndGet(23 * HOUR);
        assertNotNull(service.authenticate(token), "23 小时后仍有效，并顺延一天");
        now.addAndGet(23 * HOUR);
        assertNotNull(service.authenticate(token), "持续使用时不会在登录满 24 小时那一刻掉线");

        now.addAndGet(24 * HOUR);
        assertNull(service.authenticate(token), "闲置满一天后失效");
        assertNull(service.authenticate(token));
    }

    @Test
    void sessionTtlFollowsConfiguration() {
        properties.getSecurity().setSessionTtlHours(2);
        Session session = service.register("alice", "alice-pass-1", null);
        assertEquals(now.get() + 2 * HOUR, session.expiresAt());
        now.addAndGet(2 * HOUR);
        assertNull(service.authenticate(session.token()));
    }

    @Test
    void authenticateIgnoresUnknownTokens() {
        assertNull(service.authenticate(null));
        assertNull(service.authenticate(" "));
        assertNull(service.authenticate("not-a-session"));
    }

    @Test
    void logoutEndsOnlyThatSession() {
        String first = service.register("alice", "alice-pass-1", null).token();
        String second = service.login("alice", "alice-pass-1").token();
        service.logout(first);
        service.logout(null);
        assertNull(service.authenticate(first));
        assertNotNull(service.authenticate(second));
    }

    @Test
    void changePasswordKeepsCurrentSessionAndDropsOthers() {
        String current = service.register("alice", "alice-pass-1", null).token();
        String other = service.login("alice", "alice-pass-1").token();

        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword("alice", "wrong-pass-1", "new-pass-22", current));
        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword("alice", "alice-pass-1", "short", current));
        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword("ghost", "alice-pass-1", "new-pass-22", current));

        service.changePassword("alice", "alice-pass-1", "new-pass-22", current);
        assertNotNull(service.authenticate(current));
        assertNull(service.authenticate(other));
        assertThrows(ResponseStatusException.class, () -> service.login("alice", "alice-pass-1"));
        assertNotNull(service.login("alice", "new-pass-22"));
    }

    @Test
    void adminPromotesAndDisablesUsers() {
        Session alice = service.register("alice", "alice-pass-1", null);

        UserView promoted = service.update(alice.user().id(), new UserPatch("爱丽丝", List.of("ADMIN"), null), "root");
        assertEquals(List.of("ADMIN", "QUERY"), promoted.roles());
        assertEquals("爱丽丝", promoted.displayName());
        assertTrue(service.authenticate(alice.token()).hasRole(GatewayRole.ADMIN), "角色变更对已有会话立即生效");

        UserView disabled = service.update(alice.user().id(), new UserPatch(null, null, "disabled"), "root");
        assertEquals(UserAccount.DISABLED, disabled.status());
        assertNull(service.authenticate(alice.token()));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.login("alice", "alice-pass-1")).getStatusCode());

        service.update(alice.user().id(), new UserPatch(null, null, "active"), "root");
        assertNotNull(service.login("alice", "alice-pass-1"));
        assertEquals(2, service.list().size());
        assertEquals("alice", service.find("alice").orElseThrow().username());
    }

    @Test
    void adminCannotLockSelfOutAndInputIsValidated() {
        long rootId = service.find("root").orElseThrow().id();

        assertThrows(IllegalArgumentException.class,
                () -> service.update(rootId, new UserPatch(null, null, "disabled"), "root"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(rootId, new UserPatch(null, List.of("QUERY"), null), "root"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(rootId, new UserPatch(null, List.of("OWNER"), null), "other"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(rootId, new UserPatch(null, List.of(), null), "other"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(rootId, new UserPatch(null, null, "paused"), "other"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.update(999, new UserPatch(null, null, null), "root")).getStatusCode());
        assertEquals(List.of("ADMIN", "QUERY"), service.update(rootId, null, "root").roles());
    }

    @Test
    void adminResetPasswordDropsSessionsAndUnlocks() {
        Session alice = service.register("alice", "alice-pass-1", null);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThrows(ResponseStatusException.class, () -> service.login("alice", "wrong-pass-1"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.resetPassword(alice.user().id(), "short", "root"));

        service.resetPassword(alice.user().id(), "reset-pass-33", "root");
        assertNull(service.authenticate(alice.token()));
        assertNotNull(service.login("alice", "reset-pass-33"));
    }

    @Test
    void ensureAdminCreatesConfiguredAdminOnce() {
        store.clear();
        service = new UserService(store, properties, new BCryptPasswordEncoder(4), now::get);
        service.ensureAdmin();
        assertTrue(store.list().isEmpty(), "未配置时不建账号");

        properties.getSecurity().setAdminUsername("Root");
        properties.getSecurity().setAdminPassword("root-pass-11");
        service.ensureAdmin();
        Session session = service.login("root", "root-pass-11");
        assertEquals(List.of("ADMIN", "QUERY"), session.user().roles());

        service.changePassword("root", "root-pass-11", "changed-pass-1", session.token());
        service.ensureAdmin();
        assertEquals(1, store.list().size());
        assertNotNull(service.login("root", "changed-pass-1"), "重启不会把改过的密码覆盖回去");
    }

    @Test
    void ensureAdminRejectsUnusableConfiguration() {
        store.clear();
        service = new UserService(store, properties, new BCryptPasswordEncoder(4), now::get);
        properties.getSecurity().setAdminUsername("anonymous");
        properties.getSecurity().setAdminPassword("root-pass-11");
        assertThrows(IllegalStateException.class, service::ensureAdmin);

        properties.getSecurity().setAdminUsername("root");
        properties.getSecurity().setAdminPassword("short");
        assertThrows(IllegalStateException.class, service::ensureAdmin);
        assertTrue(store.list().isEmpty());
    }
}
