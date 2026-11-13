package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 可选 pgvector 术语召回（对齐 Auto Ontology）。未配置 URL / 连接失败时 {@link #enabled()} 为 false，
 * 调用方回退到内存/落盘/JDBC 索引，不硬依赖外部 Postgres。
 */
public final class PgvectorTermRetriever implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgvectorTermRetriever.class);
    private static final double MIN_SIM = 0.42;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int dimensions;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Boolean> synced = new ConcurrentHashMap<>();

    private PgvectorTermRetriever(String jdbcUrl, String username, String password, int dimensions, boolean enabled) {
        this.jdbcUrl = jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.dimensions = Math.max(32, dimensions);
        this.enabled = enabled;
    }

    /** 未配置或探测失败时返回 disabled 实例（永不抛）。 */
    public static PgvectorTermRetriever create(FederationProperties.Ask ask) {
        if (ask == null) {
            return disabled();
        }
        String url = ask.getPgvectorUrl();
        if (url == null || url.isBlank()) {
            return disabled();
        }
        int dim = ask.getPgvectorDimensions() > 0 ? ask.getPgvectorDimensions() : HashingTermEmbedder.DEFAULT_DIM;
        PgvectorTermRetriever candidate =
                new PgvectorTermRetriever(url.trim(), ask.getPgvectorUsername(), ask.getPgvectorPassword(), dim, true);
        try {
            candidate.ensureSchema();
            log.info("pgvector term retriever enabled (dim={})", dim);
            return candidate;
        } catch (Exception e) {
            log.warn("pgvector unavailable, falling back to local embedding index: {}", e.toString());
            return disabled();
        }
    }

    public static PgvectorTermRetriever disabled() {
        return new PgvectorTermRetriever("", "", "", HashingTermEmbedder.DEFAULT_DIM, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Match> retrieve(String question, List<Term> lexicon, TermEmbedder embedder, int limit) {
        if (!enabled || question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        TermEmbedder engine = embedder == null ? new HashingTermEmbedder(dimensions) : embedder;
        String fingerprint = OntologyEmbeddingIndex.fingerprint(lexicon, engine.name());
        try {
            syncIfNeeded(fingerprint, lexicon, engine);
            float[] query = fit(engine.embed(question));
            String source = "pgvector:" + engine.name();
            List<Match> hits = new ArrayList<>();
            try (Connection connection = open();
                    PreparedStatement statement = connection.prepareStatement(
                            """
                                    SELECT entity, kind, original, text, quality,
                                           1 - (embedding <=> ?::vector) AS score
                                    FROM ontology_term_vec
                                    WHERE fingerprint = ? AND embedder = ?
                                    ORDER BY embedding <=> ?::vector
                                    LIMIT ?
                                    """)) {
                String literal = toVectorLiteral(query);
                statement.setString(1, literal);
                statement.setString(2, fingerprint);
                statement.setString(3, engine.name());
                statement.setString(4, literal);
                statement.setInt(5, Math.max(1, limit));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        double score = rs.getDouble("score");
                        if (score < MIN_SIM) {
                            continue;
                        }
                        double quality = rs.getDouble("quality") * (0.4 + 0.5 * score);
                        hits.add(new Match(
                                rs.getString("entity"),
                                rs.getString("kind"),
                                rs.getString("original"),
                                source,
                                quality,
                                source));
                    }
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("pgvector retrieve failed, caller should fall back: {}", e.toString());
            synced.remove(fingerprint);
            return List.of();
        }
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.isFinite(vector[i]) ? vector[i] : 0f);
        }
        sb.append(']');
        return sb.toString();
    }

    float[] fit(float[] vector) {
        float[] out = new float[dimensions];
        if (vector == null || vector.length == 0) {
            return out;
        }
        System.arraycopy(vector, 0, out, 0, Math.min(vector.length, dimensions));
        return HashingTermEmbedder.l2Normalize(out);
    }

    private void syncIfNeeded(String fingerprint, List<Term> lexicon, TermEmbedder engine) throws SQLException {
        if (Boolean.TRUE.equals(synced.get(fingerprint))) {
            return;
        }
        synchronized (synced) {
            if (Boolean.TRUE.equals(synced.get(fingerprint))) {
                return;
            }
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM ontology_term_vec WHERE fingerprint = ? AND embedder = ?")) {
                    delete.setString(1, fingerprint);
                    delete.setString(2, engine.name());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        """
                                INSERT INTO ontology_term_vec
                                  (fingerprint, embedder, entity, kind, original, text, quality, embedding)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)
                                """)) {
                    for (Term term : lexicon) {
                        if (term.text() == null || term.text().isBlank()) {
                            continue;
                        }
                        insert.setString(1, fingerprint);
                        insert.setString(2, engine.name());
                        insert.setString(3, term.entity());
                        insert.setString(4, term.kind());
                        insert.setString(5, term.original() == null ? "" : term.original());
                        insert.setString(6, term.text());
                        insert.setDouble(7, term.quality());
                        insert.setString(8, toVectorLiteral(fit(engine.embed(term.text()))));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            }
            synced.put(fingerprint, Boolean.TRUE);
        }
    }

    private void ensureSchema() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute(
                    """
                            CREATE TABLE IF NOT EXISTS ontology_term_vec (
                              fingerprint VARCHAR(32) NOT NULL,
                              embedder VARCHAR(64) NOT NULL,
                              entity VARCHAR(255) NOT NULL,
                              kind VARCHAR(64) NOT NULL,
                              original TEXT,
                              text TEXT NOT NULL,
                              quality DOUBLE PRECISION NOT NULL,
                              embedding vector(%d) NOT NULL,
                              PRIMARY KEY (fingerprint, embedder, entity, text)
                            )
                            """
                            .formatted(dimensions));
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public void close() {
        synced.clear();
    }
}
