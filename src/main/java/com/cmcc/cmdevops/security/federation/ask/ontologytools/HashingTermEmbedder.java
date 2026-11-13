package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 本地特征哈希词嵌入：将字符 n-gram / 词片映射到固定维 L2 归一化向量。
 * 对齐 Auto Ontology「embedding 术语检索」的工程形态，无需外部 EMBED API / pgvector。
 */
public final class HashingTermEmbedder implements TermEmbedder {

    public static final int DEFAULT_DIM = 256;

    private final int dim;

    public HashingTermEmbedder() {
        this(DEFAULT_DIM);
    }

    public HashingTermEmbedder(int dim) {
        if (dim < 32) {
            throw new IllegalArgumentException("dim must be >= 32");
        }
        this.dim = dim;
    }

    @Override
    public String name() {
        return "hashing";
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (cleaned.length() < 2) {
            return vector;
        }
        for (int n = 2; n <= 3; n++) {
            if (cleaned.length() < n) {
                continue;
            }
            float weight = n == 2 ? 1.0f : 0.6f;
            for (int i = 0; i <= cleaned.length() - n; i++) {
                String gram = cleaned.substring(i, i + n);
                int bucket = Math.floorMod(mix64(fnv1a(gram)), dim);
                vector[bucket] += weight;
            }
        }
        // 整词片：按非字母数字切分后再哈希，利于中英混排
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+")) {
            if (token.length() < 2) {
                continue;
            }
            int bucket = Math.floorMod(mix64(fnv1a(token)), dim);
            vector[bucket] += 1.2f;
        }
        return l2Normalize(vector);
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /** L2 归一化（就地）；供 pgvector 载荷与哈希嵌入共用。 */
    public static float[] l2Normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        if (sum <= 1e-12) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }

    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= Byte.toUnsignedInt(b);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
