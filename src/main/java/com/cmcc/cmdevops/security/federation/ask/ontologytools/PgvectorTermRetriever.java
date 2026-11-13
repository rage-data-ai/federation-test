package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 可选 pgvector 术语召回（对齐 Auto Ontology）。未配置 URL / 连接失败时 {@link #enabled()} 为 false，
 * 调用方回退到内存/落盘/JDBC 索引，不硬依赖外部 Postgres。
 * ANN 索引（HNSW / IVFFlat）可选；创建失败时降级为精确扫描。
 */
public final class PgvectorTermRetriever implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgvectorTermRetriever.class);
    private static final double MIN_SIM = 0.42;

    enum IndexMode {
        NONE,
        IVFFLAT,
        HNSW;

        static IndexMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return HNSW;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "none", "off", "exact" -> NONE;
                case "ivfflat", "ivf" -> IVFFLAT;
                case "hnsw" -> HNSW;
                default -> HNSW;
            };
        }
    }

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int dimensions;
    private final IndexMode indexMode;
    private final int ivfflatLists;
    private final int hnswM;
    private final int hnswEfConstruction;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Boolean> synced = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeIndex = new AtomicReference<>("none");

    private PgvectorTermRetriever(
            String jdbcUrl,
            String username,
            String password,
            int dimensions,
            IndexMode indexMode,
            int ivfflatLists,
            int hnswM,
            int hnswEfConstruction,
            boolean enabled) {
        this.jdbcUrl = jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.dimensions = Math.max(32, dimensions);
        this.indexMode = indexMode == null ? IndexMode.HNSW : indexMode;
        this.ivfflatLists = Math.max(1, ivfflatLists);
        this.hnswM = Math.max(2, hnswM);
        this.hnswEfConstruction = Math.max(4, hnswEfConstruction);
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
        IndexMode mode = IndexMode.parse(ask.getPgvectorIndex());
        PgvectorTermRetriever candidate = new PgvectorTermRetriever(
                url.trim(),
                ask.getPgvectorUsername(),
                ask.getPgvectorPassword(),
                dim,
                mode,
                ask.getPgvectorIvfflatLists(),
                ask.getPgvectorHnswM(),
                ask.getPgvectorHnswEfConstruction(),
                true);
        try {
            candidate.ensureSchema();
            log.info("pgvector term retriever enabled (dim={}, index={})", dim, mode.name().toLowerCase(Locale.ROOT));
            return candidate;
        } catch (Exception e) {
            log.warn("pgvector unavailable, falling back to local embedding index: {}", e.toString());
            return disabled();
        }
    }

    public static PgvectorTermRetriever disabled() {
        return new PgvectorTermRetriever(
                "", "", "", HashingTermEmbedder.DEFAULT_DIM, IndexMode.NONE, 100, 16, 64, false);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 当前生效的 ANN 索引类型：{@code none}/{@code ivfflat}/{@code hnsw}。 */
    public String activeIndex() {
        return activeIndex.get();
    }

    public List<Match> retrieve(String question, List<Term> lexicon, TermEmbedder embedder, int limit) {
        if (!enabled || question == null || question.isBlank() || lexicon == null || lexicon.isEmpty()) {
            return List.of();
        }
        TermEmbedder engine = embedder == null ? new HashingTermEmbedder(dimensions) : embedder;
        String fingerprint = OntologyEmbeddingIndex.fingerprint(lexicon, engine.name());
        try {
            int rows = syncIfNeeded(fingerprint, lexicon, engine);
            ensureAnnIndex(rows);
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

    /** 构建 CREATE INDEX DDL；供单测断言。失败路径由 {@link #ensureAnnIndex(int)} 吞掉。 */
    static String annIndexDdl(IndexMode mode, int lists, int m, int efConstruction) {
        return switch (mode) {
            case NONE -> "";
            case IVFFLAT -> """
                    CREATE INDEX IF NOT EXISTS idx_ontology_term_vec_ann
                    ON ontology_term_vec
                    USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = %d)
                    """
                    .formatted(Math.max(1, lists));
            case HNSW -> """
                    CREATE INDEX IF NOT EXISTS idx_ontology_term_vec_ann
                    ON ontology_term_vec
                    USING hnsw (embedding vector_cosine_ops)
                    WITH (m = %d, ef_construction = %d)
                    """
                    .formatted(Math.max(2, m), Math.max(4, efConstruction));
        };
    }

    float[] fit(float[] vector) {
        float[] out = new float[dimensions];
        if (vector == null || vector.length == 0) {
            return out;
        }
        System.arraycopy(vector, 0, out, 0, Math.min(vector.length, dimensions));
        return HashingTermEmbedder.l2Normalize(out);
    }

    private int syncIfNeeded(String fingerprint, List<Term> lexicon, TermEmbedder engine) throws SQLException {
        if (Boolean.TRUE.equals(synced.get(fingerprint))) {
            return lexicon.size();
        }
        synchronized (synced) {
            if (Boolean.TRUE.equals(synced.get(fingerprint))) {
                return lexicon.size();
            }
            int inserted = 0;
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
                        inserted++;
                    }
                    insert.executeBatch();
                }
                connection.commit();
            }
            synced.put(fingerprint, Boolean.TRUE);
            return inserted;
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
        // 尝试建 ANN；空表时 IVFFlat 常失败 → 降级 none，同步后再试
        ensureAnnIndex(0);
    }

    private void ensureAnnIndex(int rowHint) {
        if (!enabled || indexMode == IndexMode.NONE) {
            activeIndex.set("none");
            return;
        }
        if (indexMode == IndexMode.IVFFLAT && rowHint > 0 && rowHint < ivfflatLists) {
            // lists 过大时 IVFFlat 拒绝；按行数收缩
            tryCreateIndex(IndexMode.IVFFLAT, Math.max(1, rowHint), hnswM, hnswEfConstruction);
            return;
        }
        tryCreateIndex(indexMode, ivfflatLists, hnswM, hnswEfConstruction);
    }

    private void tryCreateIndex(IndexMode mode, int lists, int m, int efConstruction) {
        String ddl = annIndexDdl(mode, lists, m, efConstruction);
        if (ddl.isBlank()) {
            activeIndex.set("none");
            return;
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            activeIndex.set(mode.name().toLowerCase(Locale.ROOT));
            log.debug("pgvector ANN index ready: {}", activeIndex.get());
        } catch (SQLException e) {
            activeIndex.set("none");
            log.warn(
                    "pgvector {} index unavailable, using exact cosine scan: {}",
                    mode.name().toLowerCase(Locale.ROOT),
                    e.toString());
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
