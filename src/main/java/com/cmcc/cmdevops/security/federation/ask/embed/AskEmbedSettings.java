package com.cmcc.cmdevops.security.federation.ask.embed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HttpTermEmbedder;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.TermEmbedder;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import jakarta.annotation.PostConstruct;

/**
 * 问数/知识库嵌入配置：环境变量作初值，控制台「模型」可改并落库，保存后热更新知识向量索引。
 */
@Component
public class AskEmbedSettings {

    private static final Logger log = LoggerFactory.getLogger(AskEmbedSettings.class);

    private final FederationProperties properties;
    private final EmbedSettingsStore store;
    private final ObjectProvider<KnowledgeVectorIndex> knowledgeVectors;

    private volatile Settings current = new Settings("", "", "");
    private volatile TermEmbedder embedder;
    private volatile int dim;

    @Autowired
    public AskEmbedSettings(
            FederationProperties properties,
            EmbedSettingsStore store,
            ObjectProvider<KnowledgeVectorIndex> knowledgeVectors) {
        this.properties = properties;
        this.store = store;
        this.knowledgeVectors = knowledgeVectors;
    }

    /** 单测：无 Spring / 无知识索引回调。 */
    AskEmbedSettings(FederationProperties properties, EmbedSettingsStore store) {
        this.properties = properties;
        this.store = store;
        this.knowledgeVectors = null;
    }

    @PostConstruct
    public void init() {
        Settings env = fromEnv();
        Settings saved = store.read();
        if (saved != null && !blank(saved.endpoint())) {
            apply(mergeKeepKey(env, saved), true);
            log.info("嵌入配置：已从 fed_ask_embed 加载 endpoint={} model={}", current.endpoint(), current.model());
            return;
        }
        apply(env, true);
        if (configured()) {
            store.write(current);
            log.info("嵌入配置：已从环境变量写入 fed_ask_embed endpoint={} model={}", current.endpoint(), current.model());
        } else {
            log.info("嵌入配置：未设置，请在控制台「模型」中填写（Milvus 知识库需要）");
        }
    }

    public boolean configured() {
        return !blank(current.endpoint());
    }

    public Settings current() {
        return current;
    }

    public TermEmbedder embedder() {
        return embedder;
    }

    public int dim() {
        return dim;
    }

    public String embedderName() {
        return embedder == null ? "none" : embedder.name();
    }

    public synchronized Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", configured());
        view.put("endpoint", current.endpoint());
        view.put("model", current.model());
        view.put("apiKeySet", !blank(current.apiKey()));
        view.put("embedder", embedderName());
        view.put("dim", dim);
        view.put("milvus", properties.getVector().getMilvusUri() != null
                && !properties.getVector().getMilvusUri().isBlank());
        return view;
    }

    public synchronized Map<String, Object> update(Settings settings) {
        current = overlay(current, settings);
        requireConfigured();
        syncProperties();
        // 探测维度；失败则不落库
        rebuildEmbedder(true);
        store.write(current);
        if (knowledgeVectors != null) {
            knowledgeVectors.ifAvailable(KnowledgeVectorIndex::reloadEmbedder);
        }
        return view();
    }

    /** 用表单值试嵌入，不保存；密钥留空沿用已保存密钥。 */
    public synchronized Map<String, Object> test(Settings settings) {
        Settings trial = overlay(current, settings);
        if (blank(trial.endpoint())) {
            throw new IllegalArgumentException("请填写嵌入接口地址");
        }
        TermEmbedder engine = httpEmbedder(trial, true);
        float[] vector = engine.embed("ping");
        return Map.of(
                "success", true,
                "message", "ok dim=" + vector.length,
                "dim", vector.length,
                "embedder", engine.name());
    }

    /** 供知识库索引在启动/热更新时取嵌入器；未配置时返回 null。 */
    public synchronized TermEmbedder requireEmbedderForMilvus() {
        if (embedder == null) {
            rebuildEmbedder(false);
        }
        return embedder;
    }

    private void apply(Settings settings, boolean fromStore) {
        current = overlay(fromStore ? new Settings("", "", "") : current, settings);
        syncProperties();
        boolean milvus = properties.getVector().getMilvusUri() != null
                && !properties.getVector().getMilvusUri().isBlank();
        try {
            rebuildEmbedder(milvus && configured());
        } catch (RuntimeException e) {
            // 启动时密钥失效/欠费不应拖垮网关；控制台可改配后再测。
            log.warn("嵌入初始化失败，请在控制台「模型」中重新配置: {}", e.toString());
            embedder = null;
            dim = 0;
        }
    }

    private void rebuildEmbedder(boolean strictHttp) {
        if (!configured()) {
            embedder = null;
            dim = 0;
            return;
        }
        TermEmbedder engine = httpEmbedder(current, strictHttp);
        float[] probe = engine.embed("dimension-probe");
        embedder = engine;
        dim = probe == null ? 0 : probe.length;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalArgumentException("请填写嵌入接口地址（OpenAI 兼容 …/embeddings）");
        }
        if (blank(current.model())) {
            throw new IllegalArgumentException("请填写嵌入模型名");
        }
    }

    private void syncProperties() {
        FederationProperties.Ask ask = properties.getAsk();
        ask.setEmbedEndpoint(current.endpoint());
        ask.setEmbedApiKey(current.apiKey());
        ask.setEmbedModel(current.model());
    }

    private Settings fromEnv() {
        FederationProperties.Ask ask = properties.getAsk();
        return new Settings(
                nullToEmpty(ask.getEmbedEndpoint()),
                nullToEmpty(ask.getEmbedApiKey()),
                nullToEmpty(ask.getEmbedModel()));
    }

    private static Settings overlay(Settings base, Settings patch) {
        String endpoint = patch.endpoint() == null ? base.endpoint() : patch.endpoint().strip();
        String model = patch.model() == null ? base.model() : patch.model().strip();
        String apiKey = base.apiKey();
        if (patch.apiKey() != null && !patch.apiKey().isBlank()) {
            apiKey = patch.apiKey().strip();
        }
        return new Settings(endpoint, apiKey, model);
    }

    /** DB 行优先；DB 密钥空则保留环境变量密钥。 */
    private static Settings mergeKeepKey(Settings env, Settings saved) {
        String key = blank(saved.apiKey()) ? env.apiKey() : saved.apiKey();
        String model = blank(saved.model()) ? env.model() : saved.model();
        return new Settings(saved.endpoint().strip(), key, model);
    }

    private static TermEmbedder httpEmbedder(Settings settings, boolean noHashFallback) {
        return new HttpTermEmbedder(
                settings.endpoint(),
                settings.apiKey(),
                blank(settings.model()) ? "embedding-3" : settings.model(),
                !noHashFallback);
    }

    /** 无 Milvus 时的本地回退（单测/开发）。 */
    public TermEmbedder hashingFallback() {
        return new HashingTermEmbedder();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    public record Settings(String endpoint, String apiKey, String model) {
    }
}
