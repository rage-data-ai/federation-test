package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * 知识向量索引。Milvus 集合字段：
 * {@code id, question, answer(问答对), year, version, dense_vector, sparse_vector}。
 * 稠密向量由 {@code question} 嵌入；稀疏向量为简易词袋（占位，供后续混合检索）。
 */
@Component
public class KnowledgeVectorIndex {

    public static final String COLLECTION = "ask_knowledge";
    public static final String DENSE_FIELD = "dense_vector";
    public static final String SPARSE_FIELD = "sparse_vector";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorIndex.class);
    private static final int SPARSE_BUCKETS = 2048;

    private final AskEmbedSettings embedSettings;
    private final RestClient milvus;
    private final ObjectMapper json;
    /** 仅无 Milvus 时使用；有 Milvus 时保持为空。 */
    private final Map<Long, MemoryRow> memory;
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
                    "知识库向量：Milvus={} embedder={} dim={}（dense_vector←question；无内存回退）",
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

    /** 嵌入配置变更后重建；维度/schema 变化时下次写入会重建 Milvus 集合。 */
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

    public void upsert(AskKnowledge item) {
        if (item == null) {
            throw new IllegalArgumentException("知识不能为空");
        }
        String question = item.question() == null ? "" : item.question();
        String answer = item.answerPair() == null || item.answerPair().answer() == null
                ? ""
                : item.answerPair().answer();
        float[] dense = embed(question);
        Map<String, Float> sparse = sparseOf(question);
        if (milvus == null) {
            memory.put(item.id(), new MemoryRow(dense, question, answer, item.yearOrDefault(), item.versionOrDefault()));
            return;
        }
        try {
            ensureCollection();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("question", question);
            row.put("answer", answer);
            row.put("year", item.yearOrDefault());
            row.put("version", item.versionOrDefault());
            row.put(DENSE_FIELD, toList(dense));
            row.put(SPARSE_FIELD, sparse);
            post("/v2/vectordb/entities/upsert", Map.of(
                    "collectionName", COLLECTION,
                    "data", List.of(row)));
        } catch (RuntimeException e) {
            log.warn("Milvus 写入知识向量失败 id={}: {}", item.id(), e.toString());
            throw e;
        }
    }

    /** @deprecated 使用 {@link #upsert(AskKnowledge)} */
    @Deprecated
    public void upsert(long id, String question) {
        upsert(new AskKnowledge(
                id,
                question,
                new AskKnowledge.AnswerPair(KnowledgeQaPair.format(question, ""), null, List.of()),
                Map.of(),
                null,
                AskKnowledge.KIND_SMART_ASK,
                AskKnowledge.APPROVED,
                "system",
                null,
                null,
                "",
                AskKnowledge.DEFAULT_VERSION,
                0L,
                0L));
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
                    "annsField", DENSE_FIELD,
                    "limit", Math.max(1, limit),
                    "outputFields", List.of("id", "question", "answer", "year", "version")));
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
                .map(entry -> new Hit(
                        entry.getKey(),
                        HashingTermEmbedder.cosine(query, entry.getValue().dense()),
                        entry.getValue().question(),
                        entry.getValue().answer(),
                        entry.getValue().year(),
                        entry.getValue().version()))
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
            SchemaStatus status = describeSchema();
            if (status == SchemaStatus.LEGACY_OR_MISMATCH) {
                log.warn("Milvus 集合 {} schema 不匹配（需 dense_vector/sparse_vector/answer/year/version），删除后重建", COLLECTION);
                try {
                    post("/v2/vectordb/collections/drop", Map.of("collectionName", COLLECTION));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("无法删除旧 Milvus 集合 " + COLLECTION + ": " + e.getMessage(), e);
                }
                status = SchemaStatus.MISSING;
            } else if (status == SchemaStatus.DIM_MISMATCH) {
                log.warn("Milvus 集合 {} 稠密向量维度与当前嵌入 dim={} 不一致，删除后重建", COLLECTION, dim);
                try {
                    post("/v2/vectordb/collections/drop", Map.of("collectionName", COLLECTION));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("无法删除维度不匹配的集合 " + COLLECTION + ": " + e.getMessage(), e);
                }
                status = SchemaStatus.MISSING;
            }
            if (status == SchemaStatus.MISSING) {
                createCollection();
            }
            collectionReady = true;
        }
    }

    private void createCollection() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("autoID", false);
        schema.put("enableDynamicField", false);
        schema.put("fields", List.of(
                field("id", "Int64", true, null),
                varchar("question", 2000),
                varchar("answer", 16000),
                varchar("year", 16),
                varchar("version", 64),
                Map.of(
                        "fieldName", DENSE_FIELD,
                        "dataType", "FloatVector",
                        "elementTypeParams", Map.of("dim", String.valueOf(dim))),
                Map.of(
                        "fieldName", SPARSE_FIELD,
                        "dataType", "SparseFloatVector")));
        List<Map<String, Object>> indexParams = List.of(
                Map.of(
                        "fieldName", DENSE_FIELD,
                        "indexName", "dense_idx",
                        "metricType", "COSINE",
                        "indexType", "AUTOINDEX"),
                Map.of(
                        "fieldName", SPARSE_FIELD,
                        "indexName", "sparse_idx",
                        "metricType", "IP",
                        "indexType", "SPARSE_INVERTED_INDEX",
                        "params", Map.of("inverted_index_algo", "DAAT_MAXSCORE")));
        post("/v2/vectordb/collections/create", Map.of(
                "collectionName", COLLECTION,
                "schema", schema,
                "indexParams", indexParams));
    }

    private static Map<String, Object> field(String name, String dataType, boolean primary, Map<String, String> params) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldName", name);
        field.put("dataType", dataType);
        if (primary) {
            field.put("isPrimary", true);
        }
        if (params != null) {
            field.put("elementTypeParams", params);
        }
        return field;
    }

    private static Map<String, Object> varchar(String name, int maxLength) {
        return field(name, "VarChar", false, Map.of("max_length", String.valueOf(maxLength)));
    }

    private SchemaStatus describeSchema() {
        try {
            String response = post("/v2/vectordb/collections/describe", Map.of("collectionName", COLLECTION));
            JsonNode fields = json.readTree(response).path("data").path("fields");
            if (!fields.isArray() || fields.isEmpty()) {
                return SchemaStatus.MISSING;
            }
            boolean hasDense = false;
            boolean hasSparse = false;
            boolean hasAnswer = false;
            boolean hasYear = false;
            boolean hasVersion = false;
            boolean hasLegacyVector = false;
            Integer denseDim = null;
            for (JsonNode field : fields) {
                String name = field.path("name").asString();
                if (name.isBlank()) {
                    name = field.path("fieldName").asString();
                }
                if ("vector".equals(name)) {
                    hasLegacyVector = true;
                }
                if (DENSE_FIELD.equals(name)) {
                    hasDense = true;
                    denseDim = readDim(field);
                }
                if (SPARSE_FIELD.equals(name)) {
                    hasSparse = true;
                }
                if ("answer".equals(name)) {
                    hasAnswer = true;
                }
                if ("year".equals(name)) {
                    hasYear = true;
                }
                if ("version".equals(name)) {
                    hasVersion = true;
                }
            }
            if (hasLegacyVector || !hasDense || !hasSparse || !hasAnswer || !hasYear || !hasVersion) {
                return SchemaStatus.LEGACY_OR_MISMATCH;
            }
            if (denseDim != null && denseDim != dim) {
                return SchemaStatus.DIM_MISMATCH;
            }
            return SchemaStatus.OK;
        } catch (RuntimeException e) {
            log.info("Milvus 集合 {} 尚不存在或无法描述: {}", COLLECTION, e.toString());
            return SchemaStatus.MISSING;
        } catch (Exception e) {
            log.info("解析 Milvus describe 失败: {}", e.toString());
            return SchemaStatus.MISSING;
        }
    }

    private static Integer readDim(JsonNode field) {
        for (JsonNode param : field.path("params")) {
            if ("dim".equals(param.path("key").asString())) {
                return Integer.parseInt(param.path("value").asString());
            }
        }
        JsonNode element = field.path("elementTypeParams").path("dim");
        if (!element.isMissingNode() && !element.isNull()) {
            return Integer.parseInt(element.asString());
        }
        return null;
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
                    String entityQuestion = row.path("question").asString("");
                    String entityAnswer = row.path("answer").asString("");
                    String year = row.path("year").asString("");
                    String version = row.path("version").asString("");
                    // 部分网关把标量放在 entity 子对象
                    JsonNode entity = row.path("entity");
                    if (entity.isObject()) {
                        if (entityQuestion.isBlank()) {
                            entityQuestion = entity.path("question").asString("");
                        }
                        if (entityAnswer.isBlank()) {
                            entityAnswer = entity.path("answer").asString("");
                        }
                        if (year.isBlank()) {
                            year = entity.path("year").asString("");
                        }
                        if (version.isBlank()) {
                            version = entity.path("version").asString("");
                        }
                        if (id == 0 && entity.has("id")) {
                            id = entity.path("id").asLong();
                        }
                    }
                    hits.add(new Hit(id, score, entityQuestion, entityAnswer, year, version));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Milvus 检索结果失败", e);
        }
    }

    /** 简易稀疏向量：按 codePoint 哈希到固定桶，保证字段非空。 */
    static Map<String, Float> sparseOf(String text) {
        Map<String, Float> sparse = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            sparse.put("0", 1.0f);
            return sparse;
        }
        text.codePoints().forEach(cp -> {
            int bucket = Math.floorMod(cp, SPARSE_BUCKETS);
            String key = String.valueOf(bucket);
            sparse.merge(key, 1.0f, Float::sum);
        });
        return sparse;
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

    private enum SchemaStatus {
        OK,
        MISSING,
        LEGACY_OR_MISMATCH,
        DIM_MISMATCH
    }

    private record MemoryRow(float[] dense, String question, String answer, String year, String version) {
    }

    /** Milvus / 内存召回命中；answer 为问答对文本。 */
    public record Hit(
            long id,
            double score,
            String question,
            String answer,
            String year,
            String version) {
        public Hit(long id, double score) {
            this(id, score, "", "", "", "");
        }
    }
}
