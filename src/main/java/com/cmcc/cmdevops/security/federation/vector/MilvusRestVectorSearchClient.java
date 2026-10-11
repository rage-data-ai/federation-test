package com.cmcc.cmdevops.security.federation.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MilvusRestVectorSearchClient implements VectorSearchClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MilvusRestVectorSearchClient(String baseUri) {
        this.restClient = RestClient.builder().baseUrl(baseUri).build();
    }

    @Override
    public List<VectorHit> search(String collection, String field, List<Double> vector, int k, String filter) {
        String collectionName = collection.contains(".")
                ? collection.substring(collection.lastIndexOf('.') + 1)
                : collection;
        Map<String, Object> body = Map.of(
                "collectionName", collectionName,
                "annsField", field,
                "data", List.of(vector),
                "limit", k,
                "filter", filter == null ? "" : filter,
                "outputFields", List.of("id")
        );
        String response = restClient.post()
                .uri("/v2/vectordb/entities/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseHits(response);
    }

    List<VectorHit> parseHits(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            List<VectorHit> hits = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode row : data) {
                    long id = row.path("id").asLong();
                    double score = row.has("distance") ? row.path("distance").asDouble() : row.path("score").asDouble();
                    hits.add(new VectorHit(id, score));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Milvus 响应失败", e);
        }
    }
}
