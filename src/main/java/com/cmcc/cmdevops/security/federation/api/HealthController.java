package com.cmcc.cmdevops.security.federation.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.PgvectorTermRetriever;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

/**
 * 公开健康检查：探测联邦配置库连通性；不含密钥，附带问数嵌入/pgvector/知识库向量运维信号。
 * DB 不可达时返回 503，供容器 HEALTHCHECK / 编排就绪探针使用。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final FederationProperties properties;
    private final FederalDataSource federal;
    private final PgvectorTermRetriever pgvector;
    private final KnowledgeVectorIndex knowledgeVectors;

    public HealthController(
            FederationProperties properties,
            FederalDataSource federal,
            PgvectorTermRetriever pgvector,
            KnowledgeVectorIndex knowledgeVectors) {
        this.properties = properties;
        this.federal = federal;
        this.pgvector = pgvector;
        this.knowledgeVectors = knowledgeVectors;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = false;
        String dbError = null;
        try {
            federal.jdbc().queryForObject("SELECT 1", Integer.class);
            dbUp = true;
        } catch (RuntimeException e) {
            dbError = e.getMessage() == null ? e.toString() : e.getMessage();
        }

        FederationProperties.Ask ask = properties.getAsk();
        Map<String, Object> askStatus = new LinkedHashMap<>();
        askStatus.put("embedStore", ask.getEmbedStore());
        askStatus.put("embedEndpointConfigured", ask.getEmbedEndpoint() != null && !ask.getEmbedEndpoint().isBlank());
        askStatus.put("pgvectorConfigured", ask.getPgvectorUrl() != null && !ask.getPgvectorUrl().isBlank());
        askStatus.put("pgvectorEnabled", pgvector.enabled());
        askStatus.put("pgvectorIndexConfigured", ask.getPgvectorIndex());
        askStatus.put("pgvectorIndexActive", pgvector.activeIndex());
        askStatus.put("knowledgeMilvus", knowledgeVectors.milvusEnabled());
        askStatus.put("knowledgeEmbedder", knowledgeVectors.embedderName());
        askStatus.put("knowledgeDim", knowledgeVectors.dim());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("federalDb", dbUp ? "UP" : "DOWN");
        if (dbError != null) {
            body.put("federalDbError", dbError.length() > 200 ? dbError.substring(0, 200) + "..." : dbError);
        }
        body.put("ask", askStatus);
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
