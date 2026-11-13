package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将术语向量索引以二进制落盘（无需 Postgres / pgvector）。
 */
public final class FileOntologyEmbeddingStore implements OntologyEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(FileOntologyEmbeddingStore.class);

    private final Path cacheDir;

    public FileOntologyEmbeddingStore(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    @Override
    public OntologyEmbeddingIndex load(String fingerprint, String embedderName) {
        if (cacheDir == null || fingerprint == null || embedderName == null) {
            return null;
        }
        Path file = cacheDir.resolve(safeName(embedderName) + "-" + fingerprint + ".bin");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            OntologyEmbeddingIndex index = OntologyEmbeddingIndex.decode(bytes, fingerprint, embedderName);
            if (index != null) {
                log.debug("Loaded ontology embedding index {} ({} terms)", file.getFileName(), index.size());
            }
            return index;
        } catch (IOException e) {
            log.warn("Ignore corrupt embedding cache {}: {}", file, e.toString());
            return null;
        }
    }

    @Override
    public void save(OntologyEmbeddingIndex index) {
        if (cacheDir == null || index == null) {
            return;
        }
        Path file = cacheDir.resolve(safeName(index.embedderName()) + "-" + index.fingerprint() + ".bin");
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, OntologyEmbeddingIndex.encode(index), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist embedding index {}: {}", file, e.toString());
        }
    }

    private static String safeName(String embedderName) {
        return embedderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
