package com.cmcc.cmdevops.security.federation.account;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

@Component
public class JdbcUserStore implements UserStore {

    private static final RowMapper<UserAccount> USER = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            parseRoles(rs.getString("roles")),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getLong("last_login_at"));

    private static final RowMapper<UserSession> SESSION = (rs, rowNum) -> new UserSession(
            rs.getString("token_hash"),
            rs.getLong("user_id"),
            rs.getLong("created_at"),
            rs.getLong("expires_at"));

    private final JdbcTemplate jdbc;

    public JdbcUserStore(FederalDataSource dataSource) {
        this.jdbc = dataSource.jdbc();
    }

    @Override
    public UserAccount insert(UserAccount account) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO fed_user
                          (username, display_name, password_hash, roles, status, created_at, updated_at, last_login_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, account.username());
                ps.setString(2, account.displayName());
                ps.setString(3, account.passwordHash());
                ps.setString(4, formatRoles(account.roles()));
                ps.setString(5, account.status());
                ps.setLong(6, account.createdAt());
                ps.setLong(7, account.updatedAt());
                ps.setLong(8, account.lastLoginAt());
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new DuplicateUsernameException(account.username());
        }
        Number key = keys.getKey();
        return account.withId(key == null ? 0L : key.longValue());
    }

    @Override
    public void update(UserAccount account) {
        jdbc.update("""
                UPDATE fed_user
                SET display_name = ?, password_hash = ?, roles = ?, status = ?, updated_at = ?, last_login_at = ?
                WHERE id = ?
                """,
                account.displayName(), account.passwordHash(), formatRoles(account.roles()), account.status(),
                account.updatedAt(), account.lastLoginAt(), account.id());
    }

    @Override
    public Optional<UserAccount> find(long id) {
        return jdbc.query("SELECT * FROM fed_user WHERE id = ?", USER, id).stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM fed_user WHERE username = ?", USER, username).stream().findFirst();
    }

    @Override
    public List<UserAccount> list() {
        return jdbc.query("SELECT * FROM fed_user ORDER BY id", USER);
    }

    @Override
    public void saveSession(UserSession session) {
        jdbc.update("""
                INSERT INTO fed_user_session (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, session.tokenHash(), session.userId(), session.createdAt(), session.expiresAt());
    }

    @Override
    public Optional<UserSession> findSession(String tokenHash) {
        return jdbc.query("SELECT * FROM fed_user_session WHERE token_hash = ?", SESSION, tokenHash)
                .stream().findFirst();
    }

    @Override
    public void extendSession(String tokenHash, long expiresAt) {
        jdbc.update("UPDATE fed_user_session SET expires_at = ? WHERE token_hash = ?", expiresAt, tokenHash);
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM fed_user_session WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteSessionsOf(long userId, String keepTokenHash) {
        if (keepTokenHash == null) {
            jdbc.update("DELETE FROM fed_user_session WHERE user_id = ?", userId);
        } else {
            jdbc.update("DELETE FROM fed_user_session WHERE user_id = ? AND token_hash <> ?",
                    userId, keepTokenHash);
        }
    }

    @Override
    public void deleteExpiredSessions(long now) {
        jdbc.update("DELETE FROM fed_user_session WHERE expires_at <= ?", now);
    }

    private static String formatRoles(Set<GatewayRole> roles) {
        return roles.stream().map(GatewayRole::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<GatewayRole> parseRoles(String raw) {
        EnumSet<GatewayRole> roles = EnumSet.noneOf(GatewayRole.class);
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    roles.add(GatewayRole.valueOf(part.trim()));
                }
            }
        }
        return roles;
    }
}
