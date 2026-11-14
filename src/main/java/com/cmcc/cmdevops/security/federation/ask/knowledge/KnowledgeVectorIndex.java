package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.TermEmbedder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只索引「问题」向量。有 {@code MILVUS_URI} 时写 Milvus；否则在进程内做余弦检索，方便单测与本地无 Milvus。
 */
@Component
public class KnowledgeVectorIndex {

    public static final String COLLECTION = "ask_knowledge";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndex.class);

    private final TermEmbedder embedder;
    private final int dim;
    private final RestClient milvus;
    private final ObjectMapper json;
    private final Map<Long, float[]> memory = new ConcurrentHashMap<>();
    private volatile boolean collectionReady;

    public KnowledgeVectorIndex(FederationProperties properties, ObjectMapper objectMapper) {
        this.embedder = OntologyToolsEmbedder.embedder(properties);
        this.dim = dimOf(embedder.embed("dimension-probe"));
        this.json = objectMapper;
        String uri = properties.getVector().getMilvusUri();
        this.milvus = uri == null || uri.isBlank()
                ? null
                : RestClient.builder().baseUrl(uri.strip()).build();
    }

    /** 单测与无 Spring 场景：可注入嵌入器；milvus 为 null 时走内存索引。 */
    public KnowledgeVectorIndex(TermEmbedder embedder, RestClient milvus, ObjectMapper objectMapper) {
        this.embedder = embedder;
        this.dim = dimOf(embedder.embed("dimension-probe"));
        this.milvus = milvus;
        this.json = objectMapper;
    }

    public int dim() {
        return dim;
    }

    public String embedderName() {
        return embedder.name();
    }

    public void upsert(long id, String question) {
        float[] vector = embedder.embed(question);
        memory.put(id, vector);
        if (milvus == null) {
            return;
        }
        ensureCollection();
        post("/v2/vectordb/entities/upsert", Map.of(
                "collectionName", COLLECTION,
                "data", List.of(Map.of(
                        "id", id,
                        "vector", toList(vector),
                        "question", question == null ? "" : question))));
    }

    public void delete(long id) {
        memory.remove(id);
        if (milvus == null) {
            return;
        }
        try {
            ensureCollection();
            post("/v2/vectordb/entities/delete", Map.of(
                    "collectionName", COLLECTION,
                    "filter", "id in [" + id + "]"));
        } catch (RuntimeException e) {
            log.warn("Milvus 删除知识向量失败 id={}: {}", id, e.toString());
        }
    }

    public List<Hit> search(String question, int limit) {
        float[] query = embedder.embed(question);
        if (milvus == null) {
            return memorySearch(query, limit);
        }
        ensureCollection();
        try {
            post("/v2/vectordb/collections/load", Map.of("collectionName", COLLECTION));
        } catch (RuntimeException ignored) {
            // 已加载时可能报错，忽略
        }
        String response = post("/v2/vectordb/entities/search", Map.of(
                "collectionName", COLLECTION,
                "data", List.of(toList(query)),
                "annsField", "vector",
                "limit", Math.max(1, limit),
                "outputFields", List.of("id", "question")));
        return parseHits(response);
    }

    private List<Hit> memorySearch(float[] query, int limit) {
        return memory.entrySet().stream()
                .map(entry -> new Hit(entry.getKey(), HashingTermEmbedder.cosine(query, entry.getValue())))
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private void ensureCollection() {
        if (collectionReady || milvus == null) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            try {
                post("/v2/vectordb/collections/create", Map.of(
                        "collectionName", COLLECTION,
                        "dimension", dim,
                        "metricType", "COSINE",
                        "primaryFieldName", "id",
                        "vectorFieldName", "vector",
                        "idType", "Int64",
                        "autoID", false));
            } catch (RuntimeException e) {
                // 已存在时 create 会失败，继续
                log.info("Milvus 集合 {} 可能已存在: {}", COLLECTION, e.toString());
            }
            collectionReady = true;
        }
    }

    private String post(String path, Object body) {
        String response = milvus.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = json.readTree(response);
            int code = root.path("code").asInt(0);
            if (code != 0) {
                throw new IllegalStateException("Milvus " + path + " 失败: " + response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Milvus 响应失败: " + response, e);
        }
        return response == null ? "" : response;
    }

    private List<Hit> parseHits(String response) {
        try {
            JsonNode data = json.readTree(response).path("data");
            List<Hit> hits = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode row : data) {
                    long id = row.path("id").asLong();
                    double score = row.has("distance") ? row.path("distance").asDouble() : row.path("score").asDouble();
                    hits.add(new Hit(id, score));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Milvus 检索结果失败", e);
        }
    }

    private static List<Double> toList(float[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add((double) value);
        }
        return values;
    }

    private static int dimOf(float[] vector) {
        return vector == null ? HashingTermEmbedder.DEFAULT_DIM : vector.length;
    }

    public record Hit(long id, double score) {
    }

    /** 与本体问数共用同一套嵌入配置，避免知识向量和术语向量各走各的。 */
    static final class OntologyToolsEmbedder {
        private OntologyToolsEmbedder() {
        }

        static TermEmbedder embedder(FederationProperties properties) {
            return com.cmcc.cmdevops.security.federation.ask.OntologyTools.termEmbedderOf(properties);
        }
    }
}
