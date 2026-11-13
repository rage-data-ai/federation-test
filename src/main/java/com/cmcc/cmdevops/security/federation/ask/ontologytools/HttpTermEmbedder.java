package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 {@code /v1/embeddings} 客户端（NVIDIA NIM / 自建均可）。
 * 失败时回退到 {@link HashingTermEmbedder}，保证问数链路不中断。
 */
public final class HttpTermEmbedder implements TermEmbedder {

    private static final Logger log = LoggerFactory.getLogger(HttpTermEmbedder.class);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper json;
    private final TermEmbedder fallback;

    public HttpTermEmbedder(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, new HashingTermEmbedder(), new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    HttpTermEmbedder(
            String endpoint,
            String apiKey,
            String model,
            TermEmbedder fallback,
            ObjectMapper json,
            HttpClient http) {
        this.endpoint = normalize(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        this.fallback = fallback;
        this.json = json;
        this.http = http;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return fallback.embed("");
        }
        try {
            return request(text);
        } catch (Exception e) {
            log.warn("HTTP embedding failed, fallback to {}: {}", fallback.name(), e.toString());
            return fallback.embed(text);
        }
    }

    private float[] request(String text) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("model", model, "input", text);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("embeddings HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        JsonNode root = json.readTree(response.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("embeddings response missing data[]");
        }
        JsonNode embedding = data.get(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IOException("embeddings response missing embedding[]");
        }
        List<Float> values = new ArrayList<>(embedding.size());
        for (JsonNode n : embedding) {
            values.add((float) n.asDouble());
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return l2Normalize(vector);
    }

    private static String normalize(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/embeddings")) {
            return base;
        }
        if (lower.endsWith("/v1")) {
            return base + "/embeddings";
        }
        return base + "/v1/embeddings";
    }

    private static float[] l2Normalize(float[] vector) {
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

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 180 ? body : body.substring(0, 180) + "…";
    }
}
