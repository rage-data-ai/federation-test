package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class BearerAuthFilterTest {

    @Test
    void allowsAnonymousWhenDisabled() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(false);
        BearerAuthFilter filter = new BearerAuthFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("anonymous", GatewayUser.current().name());
            passed.set(true);
        });
        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMissingTokenWhenEnabled() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("demo", "demo-token");
        BearerAuthFilter filter = new BearerAuthFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsConfiguredToken() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("alice", "secret");
        BearerAuthFilter filter = new BearerAuthFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("alice", GatewayUser.current().name());
            passed.set(true);
        });
        assertTrue(passed.get());
    }
}
