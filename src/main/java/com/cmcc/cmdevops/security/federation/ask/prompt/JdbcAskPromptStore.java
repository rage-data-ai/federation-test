package com.cmcc.cmdevops.security.federation.ask.prompt;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

@Component
public class JdbcAskPromptStore implements AskPromptStore {

    private final JdbcTemplate jdbc;

    public JdbcAskPromptStore(FederalDataSource dataSource) {
        this.jdbc = dataSource.jdbc();
    }

    private static final RowMapper<AskPrompt> MAPPER = (rs, rowNum) -> new AskPrompt(
            rs.getLong("id"),
            rs.getString("scope"),
            rs.getString("owner"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getInt("version"),
            rs.getString("created_by"),
            rs.getString("activated_by"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    @Override
    public AskPrompt save(AskPrompt item) {
        if (item.id() > 0) {
            jdbc.update("""
                    UPDATE fed_ask_prompt
                    SET scope = ?, owner = ?, content = ?, status = ?, source = ?, version = ?,
                        created_by = ?, activated_by = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    item.scope(), item.owner(), item.content(), item.status(), item.source(), item.version(),
                    item.createdBy(), item.activatedBy(), item.updatedAt(), item.id());
            return item;
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO fed_ask_prompt
                      (scope, owner, content, status, source, version, created_by, activated_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, item.scope());
            ps.setString(2, item.owner());
            ps.setString(3, item.content());
            ps.setString(4, item.status());
            ps.setString(5, item.source());
            ps.setInt(6, item.version());
            ps.setString(7, item.createdBy());
            ps.setString(8, item.activatedBy());
            ps.setLong(9, item.createdAt());
            ps.setLong(10, item.updatedAt());
            return ps;
        }, keys);
        Number key = keys.getKey();
        long id = key == null ? 0L : key.longValue();
        return new AskPrompt(
                id,
                item.scope(),
                item.owner(),
                item.content(),
                item.status(),
                item.source(),
                item.version(),
                item.createdBy(),
                item.activatedBy(),
                item.createdAt(),
                item.updatedAt());
    }

    @Override
    public Optional<AskPrompt> find(long id) {
        List<AskPrompt> rows = jdbc.query("SELECT * FROM fed_ask_prompt WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public List<AskPrompt> list(String scope, String owner) {
        if (scope != null && !scope.isBlank() && owner != null && !owner.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM fed_ask_prompt
                    WHERE scope = ? AND (owner = ? OR scope = 'global')
                    ORDER BY updated_at DESC
                    """, MAPPER, scope, owner);
        }
        if (scope != null && !scope.isBlank()) {
            return jdbc.query("SELECT * FROM fed_ask_prompt WHERE scope = ? ORDER BY updated_at DESC", MAPPER, scope);
        }
        if (owner != null && !owner.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM fed_ask_prompt
                    WHERE owner = ? OR scope = 'global'
                    ORDER BY updated_at DESC
                    """, MAPPER, owner);
        }
        return jdbc.query("SELECT * FROM fed_ask_prompt ORDER BY updated_at DESC", MAPPER);
    }

    @Override
    public Optional<AskPrompt> activeGlobal() {
        List<AskPrompt> rows = jdbc.query("""
                SELECT * FROM fed_ask_prompt
                WHERE scope = 'global' AND status = 'active'
                ORDER BY version DESC LIMIT 1
                """, MAPPER);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<AskPrompt> activePersonal(String owner) {
        if (owner == null || owner.isBlank()) {
            return Optional.empty();
        }
        List<AskPrompt> rows = jdbc.query("""
                SELECT * FROM fed_ask_prompt
                WHERE scope = 'personal' AND owner = ? AND status = 'active'
                ORDER BY version DESC LIMIT 1
                """, MAPPER, owner);
        return rows.stream().findFirst();
    }

    @Override
    public void delete(long id) {
        jdbc.update("DELETE FROM fed_ask_prompt WHERE id = ?", id);
    }
}
