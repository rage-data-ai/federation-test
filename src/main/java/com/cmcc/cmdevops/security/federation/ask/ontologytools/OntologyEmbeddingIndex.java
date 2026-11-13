package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 本体术语向量索引：按词表指纹缓存嵌入，避免每次问数全量重算。
 * 对齐 Auto Ontology / pgvector「持久术语索引」的工程意图；默认落盘，无需 Postgres。
 */
public final class OntologyEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(OntologyEmbeddingIndex.class);
    private static final double MIN_SIM = 0.42;
    private static final int MAGIC = 0x4f454944; // OEID
    private static final int VERSION = 1;

    private static final ConcurrentHashMap<String, OntologyEmbeddingIndex> MEMORY = new ConcurrentHashMap<>();

    private final String fingerprint;
    private final String embedderName;
    private final List<Entry> entries;

    private OntologyEmbeddingIndex(String fingerprint, String embedderName, List<Entry> entries) {
        this.fingerprint = fingerprint;
        this.embedderName = embedderName;
        this.entries = List.copyOf(entries);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int size() {
        return entries.size();
    }

    public List<Match> retrieve(String question, TermEmbedder embedder, int limit) {
        if (question == null || question.isBlank() || entries.isEmpty()) {
            return List.of();
        }
        TermEmbedder engine = embedder == null ? new HashingTermEmbedder() : embedder;
        float[] query = engine.embed(question);
        String source = "embed:" + embedderName;
        Map<String, Match> best = new LinkedHashMap<>();
        for (Entry entry : entries) {
            double sim = HashingTermEmbedder.cosine(query, entry.vector());
            if (sim < MIN_SIM) {
                continue;
            }
            double quality = entry.quality() * (0.4 + 0.5 * sim);
            Match candidate = new Match(entry.entity(), entry.kind(), entry.original(), source, quality, source);
            best.merge(entry.entity(), candidate, (a, b) -> a.quality() >= b.quality() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Match::quality).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public static OntologyEmbeddingIndex build(List<Term> lexicon, TermEmbedder embedder, Path cacheDir) {
        TermEmbedder engine = embedder == null ? new HashingTermEmbedder() : embedder;
        String fingerprint = fingerprint(lexicon, engine.name());
        OntologyEmbeddingIndex cached = MEMORY.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        if (cacheDir != null) {
            Path file = cacheDir.resolve(safeName(engine.name()) + "-" + fingerprint + ".bin");
            OntologyEmbeddingIndex fromDisk = tryLoad(file, fingerprint, engine.name());
            if (fromDisk != null) {
                MEMORY.put(fingerprint, fromDisk);
                return fromDisk;
            }
            OntologyEmbeddingIndex built = materialize(lexicon, engine, fingerprint);
            trySave(file, built);
            MEMORY.put(fingerprint, built);
            return built;
        }
        OntologyEmbeddingIndex built = materialize(lexicon, engine, fingerprint);
        MEMORY.put(fingerprint, built);
        return built;
    }

    /** 测试用：清空进程内缓存。 */
    public static void clearMemoryCache() {
        MEMORY.clear();
    }

    private static OntologyEmbeddingIndex materialize(List<Term> lexicon, TermEmbedder engine, String fingerprint) {
        List<Entry> entries = new ArrayList<>();
        for (Term term : lexicon) {
            if (term.text() == null || term.text().isBlank()) {
                continue;
            }
            entries.add(new Entry(
                    term.entity(),
                    term.kind(),
                    term.original(),
                    term.text(),
                    term.quality(),
                    engine.embed(term.text())));
        }
        return new OntologyEmbeddingIndex(fingerprint, engine.name(), entries);
    }

    static String fingerprint(List<Term> lexicon, String embedderName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(embedderName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            List<Term> sorted = lexicon.stream()
                    .sorted(Comparator.comparing(Term::entity).thenComparing(Term::text))
                    .toList();
            for (Term term : sorted) {
                digest.update(term.entity().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(term.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Double.toString(term.quality()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("fingerprint failed", e);
        }
    }

    private static String safeName(String embedderName) {
        return embedderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static OntologyEmbeddingIndex tryLoad(Path file, String fingerprint, String embedderName) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                return null;
            }
            String storedFp = data.readUTF();
            String storedEmbedder = data.readUTF();
            if (!fingerprint.equals(storedFp) || !embedderName.equals(storedEmbedder)) {
                return null;
            }
            int count = data.readInt();
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String entity = data.readUTF();
                String kind = data.readUTF();
                String original = data.readUTF();
                String text = data.readUTF();
                double quality = data.readDouble();
                int dim = data.readInt();
                float[] vector = new float[dim];
                for (int d = 0; d < dim; d++) {
                    vector[d] = data.readFloat();
                }
                entries.add(new Entry(entity, kind, original, text, quality, vector));
            }
            log.debug("Loaded ontology embedding index {} ({} terms)", file.getFileName(), entries.size());
            return new OntologyEmbeddingIndex(fingerprint, embedderName, entries);
        } catch (IOException e) {
            log.warn("Ignore corrupt embedding cache {}: {}", file, e.toString());
            return null;
        }
    }

    private static void trySave(Path file, OntologyEmbeddingIndex index) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    DataOutputStream data = new DataOutputStream(out)) {
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeUTF(index.fingerprint);
                data.writeUTF(index.embedderName);
                data.writeInt(index.entries.size());
                for (Entry entry : index.entries) {
                    data.writeUTF(entry.entity());
                    data.writeUTF(entry.kind());
                    data.writeUTF(entry.original() == null ? "" : entry.original());
                    data.writeUTF(entry.text());
                    data.writeDouble(entry.quality());
                    data.writeInt(entry.vector().length);
                    for (float v : entry.vector()) {
                        data.writeFloat(v);
                    }
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist embedding index {}: {}", file, e.toString());
        }
    }

    record Entry(String entity, String kind, String original, String text, double quality, float[] vector) {
    }
}
