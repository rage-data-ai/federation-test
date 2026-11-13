package com.cmcc.cmdevops.security.federation.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyEmbeddingIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.OntologyEmbeddingStore;

/**
 * 联邦库 JDBC 持久化术语向量索引（对齐 Auto Ontology pgvector 的「共享持久索引」意图，
 * 使用 LONGBLOB 二进制载荷，无需 Postgres 扩展）。
 */
public class JdbcOntologyEmbeddingStore implements OntologyEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOntologyEmbeddingStore.class);

    private final JdbcTemplate jdbc;

    public JdbcOntologyEmbeddingStore(FederalDataSource dataSource) {
        this.jdbc = dataSource.jdbc();
    }

    @Override
    public OntologyEmbeddingIndex load(String fingerprint, String embedderName) {
        if (fingerprint == null || embedderName == null) {
            return null;
        }
        try {
            return jdbc.query(
                    """
                            SELECT payload FROM fed_ontology_embedding
                            WHERE fingerprint = ? AND embedder = ?
                            """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        byte[] payload = rs.getBytes(1);
                        return OntologyEmbeddingIndex.decode(payload, fingerprint, embedderName);
                    },
                    fingerprint,
                    embedderName);
        } catch (DataAccessException e) {
            log.warn("Ignore JDBC embedding load failure: {}", e.toString());
            return null;
        }
    }

    @Override
    public void save(OntologyEmbeddingIndex index) {
        if (index == null) {
            return;
        }
        try {
            byte[] payload = OntologyEmbeddingIndex.encode(index);
            jdbc.update(
                    """
                            INSERT INTO fed_ontology_embedding (fingerprint, embedder, payload)
                            VALUES (?, ?, ?)
                            ON DUPLICATE KEY UPDATE payload = VALUES(payload)
                            """,
                    index.fingerprint(),
                    index.embedderName(),
                    payload);
        } catch (DataAccessException e) {
            log.warn("Failed to persist embedding index to JDBC: {}", e.toString());
        }
    }
}
