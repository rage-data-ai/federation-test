package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge.AnswerPair;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcAskKnowledgeStore implements AskKnowledgeStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAskKnowledgeStore(FederalDataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = dataSource.jdbc();
        this.json = objectMapper;
    }

    @Override
    public AskKnowledge save(AskKnowledge item) {
        if (item.id() > 0) {
            jdbc.update("""
                    UPDATE fed_ask_knowledge
                    SET question = ?, answer = ?, sql_text = ?, caliber = ?, evidence = ?, source_run_id = ?,
                        status = ?, created_by = ?, reviewed_by = ?, review_note = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    item.question(),
                    item.answerPair().answer(),
                    item.answerPair().sql(),
                    write(item.answerPair().caliber() == null ? List.of() : item.answerPair().caliber()),
                    write(item.evidence() == null ? Map.of() : item.evidence()),
                    item.sourceRunId(),
                    item.status(),
                    item.createdBy(),
                    item.reviewedBy(),
                    item.reviewNote(),
                    item.updatedAt(),
                    item.id());
            return find(item.id()).orElseThrow();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fed_ask_knowledge
                      (question, answer, sql_text, caliber, evidence, source_run_id, status,
                       created_by, reviewed_by, review_note, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, item.question());
            statement.setString(2, item.answerPair().answer());
            statement.setString(3, item.answerPair().sql());
            statement.setString(4, write(item.answerPair().caliber() == null ? List.of() : item.answerPair().caliber()));
            statement.setString(5, write(item.evidence() == null ? Map.of() : item.evidence()));
            statement.setString(6, item.sourceRunId());
            statement.setString(7, item.status());
            statement.setString(8, item.createdBy());
            statement.setString(9, item.reviewedBy());
            statement.setString(10, item.reviewNote());
            statement.setLong(11, item.createdAt());
            statement.setLong(12, item.updatedAt());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("写入知识后没有拿到 id");
        }
        return find(key.longValue()).orElseThrow();
    }

    @Override
    public Optional<AskKnowledge> find(long id) {
        List<AskKnowledge> rows = jdbc.query("""
                SELECT id, question, answer, sql_text, caliber, evidence, source_run_id, status,
                       created_by, reviewed_by, review_note, created_at, updated_at
                FROM fed_ask_knowledge WHERE id = ?
                """, mapper(), id);
        return rows.stream().findFirst();
    }

    @Override
    public List<AskKnowledge> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, question, answer, sql_text, caliber, evidence, source_run_id, status,
                           created_by, reviewed_by, review_note, created_at, updated_at
                    FROM fed_ask_knowledge ORDER BY updated_at DESC
                    """, mapper());
        }
        return jdbc.query("""
                SELECT id, question, answer, sql_text, caliber, evidence, source_run_id, status,
                       created_by, reviewed_by, review_note, created_at, updated_at
                FROM fed_ask_knowledge WHERE status = ? ORDER BY updated_at DESC
                """, mapper(), status);
    }

    @Override
    public List<AskKnowledge> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<AskKnowledge> rows = jdbc.query("""
                SELECT id, question, answer, sql_text, caliber, evidence, source_run_id, status,
                       created_by, reviewed_by, review_note, created_at, updated_at
                FROM fed_ask_knowledge WHERE id IN (%s)
                """.formatted(placeholders), mapper(), ids.toArray());
        Map<Long, AskKnowledge> byId = new LinkedHashMap<>();
        for (AskKnowledge row : rows) {
            byId.put(row.id(), row);
        }
        List<AskKnowledge> ordered = new ArrayList<>();
        for (Long id : ids) {
            AskKnowledge item = byId.get(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    @Override
    public void delete(long id) {
        jdbc.update("DELETE FROM fed_ask_knowledge WHERE id = ?", id);
    }

    private RowMapper<AskKnowledge> mapper() {
        return (rs, rowNum) -> new AskKnowledge(
                rs.getLong("id"),
                rs.getString("question"),
                new AnswerPair(
                        rs.getString("answer"),
                        rs.getString("sql_text"),
                        readList(rs.getString("caliber"))),
                readMap(rs.getString("evidence")),
                rs.getString("source_run_id"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getString("reviewed_by"),
                rs.getString("review_note"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化知识字段失败", e);
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("解析知识 evidence 失败", e);
        }
    }

    private List<Map<String, Object>> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, MAP_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("解析知识 caliber 失败", e);
        }
    }
}
