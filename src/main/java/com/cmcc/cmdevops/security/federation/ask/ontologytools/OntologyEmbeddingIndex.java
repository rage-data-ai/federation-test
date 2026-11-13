package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Match;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;

/**
 * 本体术语向量索引：按词表指纹缓存嵌入，避免每次问数全量重算。
 * 对齐 Auto Ontology / pgvector「持久术语索引」的工程意图；默认落盘，可叠加联邦库 JDBC。
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

    public String embedderName() {
        return embedderName;
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
        OntologyEmbeddingStore store = cacheDir == null ? null : new FileOntologyEmbeddingStore(cacheDir);
        return build(lexicon, embedder, store);
    }

    public static OntologyEmbeddingIndex build(List<Term> lexicon, TermEmbedder embedder, OntologyEmbeddingStore store) {
        TermEmbedder engine = embedder == null ? new HashingTermEmbedder() : embedder;
        String fingerprint = fingerprint(lexicon, engine.name());
        OntologyEmbeddingIndex cached = MEMORY.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        if (store != null) {
            OntologyEmbeddingIndex fromStore = store.load(fingerprint, engine.name());
            if (fromStore != null) {
                MEMORY.put(fingerprint, fromStore);
                return fromStore;
            }
            OntologyEmbeddingIndex built = materialize(lexicon, engine, fingerprint);
            store.save(built);
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

    public static byte[] encode(OntologyEmbeddingIndex index) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
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
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode embedding index failed", e);
        }
    }

    /**
     * @param expectedFingerprint 期望指纹；不一致则返回 null
     * @param expectedEmbedder 期望嵌入器名；不一致则返回 null
     */
    public static OntologyEmbeddingIndex decode(byte[] payload, String expectedFingerprint, String expectedEmbedder) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                return null;
            }
            String storedFp = data.readUTF();
            String storedEmbedder = data.readUTF();
            if (!expectedFingerprint.equals(storedFp) || !expectedEmbedder.equals(storedEmbedder)) {
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
            return new OntologyEmbeddingIndex(storedFp, storedEmbedder, entries);
        } catch (IOException e) {
            log.warn("Ignore corrupt embedding payload: {}", e.toString());
            return null;
        }
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

    record Entry(String entity, String kind, String original, String text, double quality, float[] vector) {
    }
}
