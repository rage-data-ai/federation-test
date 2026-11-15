package com.cmcc.cmdevops.security.federation.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.account.UserStore.DuplicateUsernameException;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

import jakarta.annotation.PostConstruct;

/**
 * 本地账号：自助注册、密码登录、会话校验，以及管理员对账号的维护。
 * 登录后发放不透明令牌，库里只存它的 SHA-256；账号被禁用或会话被删后下一次请求即失效。
 */
@Service
public class UserService {

    static final int MIN_PASSWORD_CHARS = 8;
    /** BCrypt 只取前 72 字节，超出的部分不参与校验，所以直接拒绝。 */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_DISPLAY_NAME = 64;

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{2,31}");
    private static final long MAX_RENEW_STEP_MS = 3_600_000L;

    private final UserStore store;
    private final FederationProperties properties;
    private final PasswordEncoder encoder;
    private final LongSupplier clock;
    private final LoginThrottle throttle = new LoginThrottle();
    private final SecureRandom random = new SecureRandom();
    /** 用户名不存在时也比对一次哈希，让两种失败耗时接近。 */
    private final String absentUserHash;

    public record Session(String token, long expiresAt, UserView user) {
    }

    /** 管理员可改的字段；为 null 的不改。 */
    public record UserPatch(String displayName, List<String> roles, String status) {
        public UserPatch {
            roles = roles == null ? null : List.copyOf(roles);
        }
    }

    @Autowired
    public UserService(UserStore store, FederationProperties properties) {
        this(store, properties, new BCryptPasswordEncoder(), System::currentTimeMillis);
    }

    public UserService(
            UserStore store,
            FederationProperties properties,
            PasswordEncoder encoder,
            LongSupplier clock) {
        this.store = store;
        this.properties = properties;
        this.encoder = encoder;
        this.clock = clock;
        this.absentUserHash = encoder.encode("absent-user");
    }

    /** 按配置补上启动管理员；账号已存在则不动。 */
    @PostConstruct
    public void ensureAdmin() {
        String name = properties.getSecurity().getAdminUsername().trim().toLowerCase(Locale.ROOT);
        String password = properties.getSecurity().getAdminPassword();
        if (name.isEmpty() || password.isEmpty()) {
            return;
        }
        if (!USERNAME.matcher(name).matches() || reserved(name)) {
            throw new IllegalStateException("启动管理员用户名不可用：" + name);
        }
        try {
            checkPassword(password);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("启动管理员密码不合要求：" + e.getMessage(), e);
        }
        if (store.findByUsername(name).isPresent()) {
            return;
        }
        long now = clock.getAsLong();
        try {
            store.insert(new UserAccount(
                    0, name, name, encoder.encode(password),
                    EnumSet.of(GatewayRole.ADMIN, GatewayRole.QUERY), UserAccount.ACTIVE, now, now, 0));
            log.info("已创建启动管理员 {}", name);
        } catch (DuplicateUsernameException e) {
            log.debug("启动管理员 {} 已由其他实例创建", name);
        }
    }

    public Session register(String username, String password, String displayName) {
        requireSecurityEnabled();
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("用户名需为 3–32 位小写字母、数字或 . _ -，且以字母或数字开头");
        }
        checkPassword(password);
        if (reserved(name) || store.findByUsername(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被占用");
        }
        long now = clock.getAsLong();
        UserAccount created;
        try {
            created = store.insert(new UserAccount(
                    0, name, cleanDisplayName(displayName, name), encoder.encode(password),
                    Set.of(GatewayRole.QUERY), UserAccount.ACTIVE, now, now, now));
        } catch (DuplicateUsernameException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被占用", e);
        }
        log.info("AUDIT register user={}", name);
        return openSession(created, now);
    }

    public Session login(String username, String password) {
        requireSecurityEnabled();
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw badCredentials();
        }
        long now = clock.getAsLong();
        long wait = throttle.retryAfterMs(name, now);
        if (wait > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 " + Math.max(1, (wait + 59_999) / 60_000) + " 分钟后再试");
        }
        Optional<UserAccount> found = store.findByUsername(name);
        boolean matches = passwordMatches(password, found.map(UserAccount::passwordHash).orElse(absentUserHash));
        if (found.isEmpty() || !matches) {
            throttle.recordFailure(name, now);
            log.info("AUDIT login-failed user={}", name);
            throw badCredentials();
        }
        UserAccount account = found.get();
        if (!account.active()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用，请联系管理员");
        }
        throttle.reset(name);
        store.deleteExpiredSessions(now);
        UserAccount updated = account.withLastLoginAt(now);
        store.update(updated);
        log.info("AUDIT login user={}", name);
        return openSession(updated, now);
    }

    /** 令牌对应的有效会话所属用户；不是本地会话、已过期或账号被禁用时返回 null。 */
    public GatewayUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String tokenHash = hash(token);
        Optional<UserSession> session = store.findSession(tokenHash);
        if (session.isEmpty()) {
            return null;
        }
        long now = clock.getAsLong();
        if (session.get().expiresAt() <= now) {
            store.deleteSession(tokenHash);
            return null;
        }
        Optional<UserAccount> account = store.find(session.get().userId());
        if (account.isEmpty() || !account.get().active()) {
            return null;
        }
        long ttl = sessionTtlMs();
        if (session.get().expiresAt() - now < ttl - Math.min(MAX_RENEW_STEP_MS, ttl / 24)) {
            store.extendSession(tokenHash, now + ttl);
        }
        return new GatewayUser(account.get().username(), account.get().roles());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            store.deleteSession(hash(token));
        }
    }

    /** 改自己的密码；成功后除当前会话外，该账号的其他会话全部失效。 */
    public void changePassword(String username, String oldPassword, String newPassword, String currentToken) {
        UserAccount account = store.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("当前身份不是本地账号，不能在这里改密码"));
        if (!passwordMatches(oldPassword, account.passwordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        checkPassword(newPassword);
        store.update(account.withPasswordHash(encoder.encode(newPassword), clock.getAsLong()));
        store.deleteSessionsOf(account.id(), currentToken == null ? null : hash(currentToken));
        log.info("AUDIT password-changed user={}", account.username());
    }

    public Optional<UserView> find(String username) {
        return store.findByUsername(username).map(UserView::of);
    }

    public List<UserView> list() {
        return store.list().stream().map(UserView::of).toList();
    }

    public UserView update(long id, UserPatch patch, String actor) {
        UserAccount account = require(id);
        if (patch == null) {
            return UserView.of(account);
        }
        String displayName = patch.displayName() == null
                ? account.displayName()
                : cleanDisplayName(patch.displayName(), account.username());
        Set<GatewayRole> roles = patch.roles() == null ? account.roles() : parseRoles(patch.roles());
        String status = patch.status() == null ? account.status() : patch.status().trim().toLowerCase(Locale.ROOT);
        if (!UserAccount.ACTIVE.equals(status) && !UserAccount.DISABLED.equals(status)) {
            throw new IllegalArgumentException("未知的账号状态：" + patch.status());
        }
        boolean self = account.username().equals(actor);
        if (self && (!UserAccount.ACTIVE.equals(status) || !roles.contains(GatewayRole.ADMIN))) {
            throw new IllegalArgumentException("不能禁用自己，也不能去掉自己的管理员角色");
        }
        UserAccount updated = account.withProfile(displayName, roles, status, clock.getAsLong());
        store.update(updated);
        if (!updated.active()) {
            store.deleteSessionsOf(id, null);
        }
        log.info("AUDIT user-updated user={} roles={} status={} by={}",
                updated.username(), updated.roles(), status, actor);
        return UserView.of(updated);
    }

    /** 管理员重置密码；该账号的所有会话立即失效。 */
    public void resetPassword(long id, String newPassword, String actor) {
        UserAccount account = require(id);
        checkPassword(newPassword);
        store.update(account.withPasswordHash(encoder.encode(newPassword), clock.getAsLong()));
        store.deleteSessionsOf(id, null);
        throttle.reset(account.username());
        log.info("AUDIT password-reset user={} by={}", account.username(), actor);
    }

    private Session openSession(UserAccount account, long now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = now + sessionTtlMs();
        store.saveSession(new UserSession(hash(token), account.id(), now, expiresAt));
        return new Session(token, expiresAt, UserView.of(account));
    }

    private UserAccount require(long id) {
        return store.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在：" + id));
    }

    private void requireSecurityEnabled() {
        if (!properties.getSecurity().isEnabled()) {
            throw new IllegalStateException("网关未开启鉴权，无需登录；设置 FEDERATION_SECURITY_ENABLED=true 后可用");
        }
    }

    private long sessionTtlMs() {
        return Math.max(1, properties.getSecurity().getSessionTtlHours()) * 3_600_000L;
    }

    /**
     * 配置里按用户名发令牌、授角色、授 catalog、挂行级规则的名字不开放注册，
     * 否则注册同名账号就能继承这些授权。
     */
    private boolean reserved(String name) {
        if (GatewayUser.ANONYMOUS.name().equals(name)) {
            return true;
        }
        FederationProperties.Security security = properties.getSecurity();
        return List.of(security.getTokens(), security.getRoles(), security.getCatalogs(), security.getRowFilters())
                .stream()
                .flatMap(map -> map.keySet().stream())
                .anyMatch(key -> key != null && key.trim().toLowerCase(Locale.ROOT).equals(name));
    }

    private boolean passwordMatches(String password, String passwordHash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return false;
        }
        return encoder.matches(password, passwordHash);
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_CHARS + " 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("密码过长，最多 " + MAX_PASSWORD_BYTES + " 字节");
        }
    }

    private static String cleanDisplayName(String displayName, String fallback) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.length() > MAX_DISPLAY_NAME) {
            throw new IllegalArgumentException("显示名最多 " + MAX_DISPLAY_NAME + " 个字符");
        }
        return value;
    }

    private static Set<GatewayRole> parseRoles(List<String> raw) {
        EnumSet<GatewayRole> roles = EnumSet.noneOf(GatewayRole.class);
        for (String item : raw) {
            try {
                GatewayRole role = GatewayRole.parse(item);
                if (role != null) {
                    roles.add(role);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知的角色：" + item, e);
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("至少保留一个角色");
        }
        if (roles.contains(GatewayRole.ADMIN)) {
            roles.add(GatewayRole.QUERY);
        }
        return roles;
    }

    private static ResponseStatusException badCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE 缺少 SHA-256", e);
        }
    }
}
