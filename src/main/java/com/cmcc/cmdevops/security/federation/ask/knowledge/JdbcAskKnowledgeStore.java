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

    private static final String SELECT_COLS = """
            id, question, answer, sql_text, caliber, evidence, source_run_id, kind, status,
            created_by, reviewed_by, review_note, year, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAskKnowledgeStore(FederalDataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = dataSource.jdbc();
        this.json = objectMapper;
    }

    @Override
    public AskKnowledge save(AskKnowledge item) {
        String kind = item.kindOrDefault();
        String version = item.versionOrDefault();
        String year = blankToNull(item.year());
        if (item.id() > 0) {
            jdbc.update("""
                    UPDATE fed_ask_knowledge
                    SET question = ?, answer = ?, sql_text = ?, caliber = ?, evidence = ?, source_run_id = ?,
                        kind = ?, status = ?, created_by = ?, reviewed_by = ?, review_note = ?,
                        year = ?, version = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    item.question(),
                    item.answerPair().answer(),
                    item.answerPair().sql(),
                    write(item.answerPair().caliber() == null ? List.of() : item.answerPair().caliber()),
                    write(item.evidence() == null ? Map.of() : item.evidence()),
                    item.sourceRunId(),
                    kind,
                    item.status(),
                    item.createdBy(),
                    item.reviewedBy(),
                    item.reviewNote(),
                    year,
                    version,
                    item.updatedAt(),
                    item.id());
            return find(item.id()).orElseThrow();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fed_ask_knowledge
                      (question, answer, sql_text, caliber, evidence, source_run_id, kind, status,
                       created_by, reviewed_by, review_note, year, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, item.question());
            statement.setString(2, item.answerPair().answer());
            statement.setString(3, item.answerPair().sql());
            statement.setString(4, write(item.answerPair().caliber() == null ? List.of() : item.answerPair().caliber()));
            statement.setString(5, write(item.evidence() == null ? Map.of() : item.evidence()));
            statement.setString(6, item.sourceRunId());
            statement.setString(7, kind);
            statement.setString(8, item.status());
            statement.setString(9, item.createdBy());
            statement.setString(10, item.reviewedBy());
            statement.setString(11, item.reviewNote());
            statement.setString(12, year);
            statement.setString(13, version);
            statement.setLong(14, item.createdAt());
            statement.setLong(15, item.updatedAt());
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
        List<AskKnowledge> rows = jdbc.query(
                "SELECT " + SELECT_COLS + " FROM fed_ask_knowledge WHERE id = ?",
                mapper(),
                id);
        return rows.stream().findFirst();
    }

    @Override
    public List<AskKnowledge> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(
                    "SELECT " + SELECT_COLS + " FROM fed_ask_knowledge ORDER BY updated_at DESC",
                    mapper());
        }
        return jdbc.query(
                "SELECT " + SELECT_COLS + " FROM fed_ask_knowledge WHERE status = ? ORDER BY updated_at DESC",
                mapper(),
                status);
    }

    @Override
    public List<AskKnowledge> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<AskKnowledge> rows = jdbc.query("""
                SELECT %s FROM fed_ask_knowledge WHERE id IN (%s)
                """.formatted(SELECT_COLS, placeholders), mapper(), ids.toArray());
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
        return (rs, rowNum) -> {
            String kind = rs.getString("kind");
            return new AskKnowledge(
                    rs.getLong("id"),
                    rs.getString("question"),
                    new AnswerPair(
                            rs.getString("answer"),
                            rs.getString("sql_text"),
                            readList(rs.getString("caliber"))),
                    readMap(rs.getString("evidence")),
                    rs.getString("source_run_id"),
                    kind == null || kind.isBlank() ? AskKnowledge.KIND_SMART_ASK : kind,
                    rs.getString("status"),
                    rs.getString("created_by"),
                    rs.getString("reviewed_by"),
                    rs.getString("review_note"),
                    rs.getString("year"),
                    rs.getString("version"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
