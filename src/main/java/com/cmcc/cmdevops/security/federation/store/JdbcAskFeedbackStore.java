package com.cmcc.cmdevops.security.federation.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskSuggestion;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcAskFeedbackStore implements AskFeedbackStore {

    private static final String SUGGESTION_SELECT = """
            SELECT s.id, s.run_id, r.question AS run_question, s.user_name, s.content, s.status,
                   s.disposition, s.reply, s.handled_by, s.created_at, s.updated_at
            FROM fed_ask_suggestion s
            LEFT JOIN fed_ask_run r ON r.id = s.run_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAskFeedbackStore(FederalDataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = dataSource.jdbc();
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveRun(AskRun run) {
        jdbc.update("""
                INSERT INTO fed_ask_run
                  (id, conversation_id, user_name, question, history, status, answer, sql_text, evidence, error,
                   created_at, elapsed_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.id(),
                run.conversationId(),
                run.userName(),
                run.question(),
                objectMapper.writeValueAsString(run.history()),
                run.status(),
                run.answer(),
                run.sql(),
                objectMapper.writeValueAsString(run.evidence()),
                run.error(),
                run.createdAt(),
                run.elapsedMs());
    }

    @Override
    public Optional<AskRun> findRun(String id) {
        List<AskRun> rows = jdbc.query("""
                SELECT id, conversation_id, user_name, question, history, status, answer, sql_text, evidence, error,
                       created_at, elapsed_ms
                FROM fed_ask_run WHERE id = ?
                """, (rs, row) -> new AskRun(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("user_name"),
                rs.getString("question"),
                objectMapper.readValue(rs.getString("history"), new TypeReference<List<Turn>>() {
                }),
                rs.getString("status"),
                rs.getString("answer"),
                rs.getString("sql_text"),
                objectMapper.readValue(rs.getString("evidence"), new TypeReference<Map<String, Object>>() {
                }),
                rs.getString("error"),
                rs.getLong("created_at"),
                rs.getLong("elapsed_ms")), id);
        return rows.stream().findFirst();
    }

    @Override
    public void rate(String runId, String userName, String rating, long now) {
        if (rating == null) {
            jdbc.update("DELETE FROM fed_ask_feedback WHERE run_id = ? AND user_name = ?", runId, userName);
            return;
        }
        jdbc.update("""
                INSERT INTO fed_ask_feedback (run_id, user_name, rating, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rating = VALUES(rating), updated_at = VALUES(updated_at)
                """, runId, userName, rating, now, now);
    }

    @Override
    public Optional<String> ratingOf(String runId, String userName) {
        return jdbc.query("SELECT rating FROM fed_ask_feedback WHERE run_id = ? AND user_name = ?",
                (rs, row) -> rs.getString(1), runId, userName).stream().findFirst();
    }

    @Override
    public AskSuggestion addSuggestion(String runId, String userName, String content, long now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fed_ask_suggestion (run_id, user_name, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, runId);
            statement.setString(2, userName);
            statement.setString(3, content);
            statement.setString(4, AskSuggestion.OPEN);
            statement.setLong(5, now);
            statement.setLong(6, now);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("建议保存后没有拿到 id");
        }
        return findSuggestion(id.longValue()).orElseThrow();
    }

    @Override
    public Optional<AskSuggestion> findSuggestion(long id) {
        return jdbc.query(SUGGESTION_SELECT + " WHERE s.id = ?", JdbcAskFeedbackStore::suggestion, id)
                .stream().findFirst();
    }

    @Override
    public List<AskSuggestion> listSuggestions(String userName, String status) {
        StringBuilder sql = new StringBuilder(SUGGESTION_SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (userName != null) {
            sql.append(" AND s.user_name = ?");
            args.add(userName);
        }
        if (status != null) {
            sql.append(" AND s.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY s.created_at DESC, s.id DESC");
        return jdbc.query(sql.toString(), JdbcAskFeedbackStore::suggestion, args.toArray());
    }

    @Override
    public void updateSuggestion(AskSuggestion suggestion) {
        jdbc.update("""
                UPDATE fed_ask_suggestion
                SET content = ?, status = ?, disposition = ?, reply = ?, handled_by = ?, updated_at = ?
                WHERE id = ?
                """,
                suggestion.content(),
                suggestion.status(),
                suggestion.disposition(),
                suggestion.reply(),
                suggestion.handledBy(),
                suggestion.updatedAt(),
                suggestion.id());
    }

    @Override
    public void deleteSuggestion(long id) {
        jdbc.update("DELETE FROM fed_ask_suggestion WHERE id = ?", id);
    }

    private static AskSuggestion suggestion(ResultSet rs, int row) throws SQLException {
        return new AskSuggestion(
                rs.getLong("id"),
                rs.getString("run_id"),
                rs.getString("run_question"),
                rs.getString("user_name"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("disposition"),
                rs.getString("reply"),
                rs.getString("handled_by"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
