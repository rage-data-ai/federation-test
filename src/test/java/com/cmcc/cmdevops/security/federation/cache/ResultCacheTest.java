package com.cmcc.cmdevops.security.federation.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class ResultCacheTest {

    private ResultCache cache;

    @BeforeEach
    void setUp() {
        FederationProperties properties = new FederationProperties();
        properties.getCache().setEnabled(true);
        properties.getCache().setTtlSeconds(60);
        properties.getCache().setMaxSize(16);
        cache = new ResultCache(properties);
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void isolatesCacheByUser() {
        QueryRequest request = new QueryRequest();
        request.setSql("SELECT 1");
        QueryResponse response = new QueryResponse(true, EngineType.TRINO.name(), "ok", List.of(), "SELECT 1",
                List.of(), List.of(), 0, 1, false, null);
        cache.put(request, response);
        assertNotNull(cache.get(request));

        GatewayUser.set(new GatewayUser("bob", Set.of(GatewayRole.QUERY)));
        assertNull(cache.get(request));
    }

    @Test
    void skipsExplainAndFailures() {
        QueryRequest explain = new QueryRequest();
        explain.setSql("SELECT 1");
        explain.setExplain(true);
        QueryResponse ok = new QueryResponse(true, EngineType.TRINO.name(), "ok", List.of(), "SELECT 1",
                List.of(), List.of(), 0, 1, false, null);
        cache.put(explain, ok);
        assertNull(cache.get(explain));

        QueryRequest request = new QueryRequest();
        request.setSql("SELECT 2");
        cache.put(request, QueryResponse.fail("boom", 1));
        assertNull(cache.get(request));
        assertTrue(true);
    }
}
