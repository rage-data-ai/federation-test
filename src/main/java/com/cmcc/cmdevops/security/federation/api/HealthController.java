package com.cmcc.cmdevops.security.federation.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.PgvectorTermRetriever;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 公开健康检查：不含密钥，附带问数嵌入/pgvector/知识库向量运维信号。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final FederationProperties properties;
    private final PgvectorTermRetriever pgvector;
    private final KnowledgeVectorIndex knowledgeVectors;

    public HealthController(
            FederationProperties properties, PgvectorTermRetriever pgvector, KnowledgeVectorIndex knowledgeVectors) {
        this.properties = properties;
        this.pgvector = pgvector;
        this.knowledgeVectors = knowledgeVectors;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
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
        body.put("status", "UP");
        body.put("ask", askStatus);
        return body;
    }
}
