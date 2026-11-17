package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.TermEmbedder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 只索引「问题」向量。
 * <ul>
 *   <li>配置了 {@code MILVUS_URI}：只写/查 Milvus，嵌入由控制台/环境变量配置（禁止哈希回退）。</li>
 *   <li>未配置 Milvus：进程内余弦检索（单测 / 本地无向量库）。</li>
 * </ul>
 */
@Component
public class KnowledgeVectorIndex {

    public static final String COLLECTION = "ask_knowledge";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndex.class);

    private final AskEmbedSettings embedSettings;
    private final RestClient milvus;
    private final ObjectMapper json;
    /** 仅无 Milvus 时使用；有 Milvus 时保持为空。 */
    private final Map<Long, float[]> memory;
    private volatile TermEmbedder embedder;
    private volatile int dim;
    private volatile boolean collectionReady;

    @Autowired
    public KnowledgeVectorIndex(
            FederationProperties properties, ObjectMapper objectMapper, AskEmbedSettings embedSettings) {
        this.json = objectMapper;
        this.embedSettings = embedSettings;
        String uri = properties.getVector().getMilvusUri();
        boolean milvusEnabled = uri != null && !uri.isBlank();
        this.milvus = milvusEnabled ? RestClient.builder().baseUrl(uri.strip()).build() : null;
        this.memory = milvusEnabled ? Map.of() : new ConcurrentHashMap<>();
        reloadEmbedder();
        if (milvusEnabled) {
            log.info(
                    "知识库向量：Milvus={} embedder={} dim={}（无内存回退；可在控制台「模型」改嵌入）",
                    uri.strip(),
                    embedderName(),
                    dim);
        } else {
            log.info("知识库向量：未配置 Milvus，使用进程内索引 embedder={}", embedderName());
        }
    }

    /** 单测：milvus 为 null 时走内存索引。 */
    public KnowledgeVectorIndex(TermEmbedder embedder, RestClient milvus, ObjectMapper objectMapper) {
        this.embedSettings = null;
        this.embedder = embedder;
        this.dim = dimOf(embedder.embed("dimension-probe"));
        this.milvus = milvus;
        this.json = objectMapper;
        this.memory = milvus == null ? new ConcurrentHashMap<>() : Map.of();
    }

    /** 嵌入配置变更后重建；维度变化时下次写入会重建 Milvus 集合。 */
    public synchronized void reloadEmbedder() {
        collectionReady = false;
        if (milvus == null) {
            TermEmbedder next = embedSettings == null
                    ? embedder
                    : (embedSettings.configured()
                            ? embedSettings.requireEmbedderForMilvus()
                            : embedSettings.hashingFallback());
            if (next == null) {
                next = new HashingTermEmbedder();
            }
            this.embedder = next;
            this.dim = dimOf(next.embed("dimension-probe"));
            return;
        }
        if (embedSettings == null || !embedSettings.configured()) {
            this.embedder = null;
            this.dim = 0;
            log.warn("已启用 Milvus，但嵌入尚未配置：请在控制台「模型」填写嵌入接口与模型名");
            return;
        }
        TermEmbedder next = embedSettings.requireEmbedderForMilvus();
        this.embedder = next;
        this.dim = embedSettings.dim() > 0 ? embedSettings.dim() : dimOf(next.embed("dimension-probe"));
        log.info("知识库嵌入已更新 embedder={} dim={}", embedderName(), dim);
    }

    public int dim() {
        return dim;
    }

    public String embedderName() {
        return embedder == null ? "none" : embedder.name();
    }

    public boolean milvusEnabled() {
        return milvus != null;
    }

    public void upsert(long id, String question) {
        float[] vector = embed(question);
        if (milvus == null) {
            memory.put(id, vector);
            return;
        }
        try {
            ensureCollection();
            post("/v2/vectordb/entities/upsert", Map.of(
                    "collectionName", COLLECTION,
                    "data", List.of(Map.of(
                            "id", id,
                            "vector", toList(vector),
                            "question", question == null ? "" : question))));
        } catch (RuntimeException e) {
            log.warn("Milvus 写入知识向量失败 id={}: {}", id, e.toString());
            throw e;
        }
    }

    public void delete(long id) {
        if (milvus == null) {
            memory.remove(id);
            return;
        }
        try {
            ensureCollection();
            post("/v2/vectordb/entities/delete", Map.of(
                    "collectionName", COLLECTION,
                    "filter", "id in [" + id + "]"));
        } catch (RuntimeException e) {
            log.warn("Milvus 删除知识向量失败 id={}: {}", id, e.toString());
            throw e;
        }
    }

    public List<Hit> search(String question, int limit) {
        float[] query = embed(question);
        if (milvus == null) {
            return memorySearch(query, limit);
        }
        try {
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
        } catch (RuntimeException e) {
            log.warn("Milvus 检索失败（不回退内存）: {}", e.toString());
            return List.of();
        }
    }

    private float[] embed(String question) {
        TermEmbedder engine = embedder;
        if (engine == null) {
            throw new IllegalStateException("嵌入模型未配置：请打开控制台左侧「模型」，填写嵌入接口地址与模型名");
        }
        return engine.embed(question);
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
            if (dim <= 0) {
                throw new IllegalStateException("嵌入维度未知：请先在控制台配置并测试嵌入模型");
            }
            Integer existingDim = describeCollectionDim();
            if (existingDim != null && existingDim != dim) {
                log.warn("Milvus 集合 {} 维度 {} 与当前嵌入 dim={} 不一致，删除后重建", COLLECTION, existingDim, dim);
                try {
                    post("/v2/vectordb/collections/drop", Map.of("collectionName", COLLECTION));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "无法删除维度不匹配的 Milvus 集合 " + COLLECTION + ": " + e.getMessage(), e);
                }
                existingDim = null;
            }
            if (existingDim == null) {
                post("/v2/vectordb/collections/create", Map.of(
                        "collectionName", COLLECTION,
                        "dimension", dim,
                        "metricType", "COSINE",
                        "primaryFieldName", "id",
                        "vectorFieldName", "vector",
                        "idType", "Int64",
                        "autoID", false));
            }
            collectionReady = true;
        }
    }

    private Integer describeCollectionDim() {
        try {
            String response = post("/v2/vectordb/collections/describe", Map.of("collectionName", COLLECTION));
            JsonNode fields = json.readTree(response).path("data").path("fields");
            if (!fields.isArray()) {
                return null;
            }
            for (JsonNode field : fields) {
                if ("vector".equals(field.path("name").asString())) {
                    for (JsonNode param : field.path("params")) {
                        if ("dim".equals(param.path("key").asString())) {
                            return Integer.parseInt(param.path("value").asString());
                        }
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.info("Milvus 集合 {} 尚不存在或无法描述: {}", COLLECTION, e.toString());
            return null;
        } catch (Exception e) {
            log.info("解析 Milvus describe 失败: {}", e.toString());
            return null;
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
}
