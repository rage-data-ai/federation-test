package com.cmcc.cmdevops.security.federation.cache;

import java.time.Duration;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Component
public class ResultCache {

    private final FederationProperties properties;
    private final Cache<String, QueryResponse> cache;

    public ResultCache(FederationProperties properties) {
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getTtlSeconds()))
                .build();
    }

    public QueryResponse get(QueryRequest request) {
        if (!properties.getCache().isEnabled() || request.isSkipCache() || request.isExplain()) {
            return null;
        }
        return cache.getIfPresent(key(request));
    }

    public void put(QueryRequest request, QueryResponse response) {
        if (!properties.getCache().isEnabled() || request.isSkipCache() || request.isExplain() || !response.success()) {
            return;
        }
        cache.put(key(request), response);
    }

    private String key(QueryRequest request) {
        return request.getSql().trim() + "|" + Objects.toString(request.getParameters(), "{}")
                + "|" + Objects.toString(request.getEngine(), "");
    }
}
