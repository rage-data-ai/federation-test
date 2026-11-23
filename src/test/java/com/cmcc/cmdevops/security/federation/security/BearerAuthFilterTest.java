package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.cmcc.cmdevops.security.federation.account.MemoryUserStore;
import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class BearerAuthFilterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JwtAccessTokenService> noJwt() {
        ObjectProvider<JwtAccessTokenService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static ObjectProvider<UserService> noUsers() {
        return usersOf(null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<UserService> usersOf(UserService service) {
        ObjectProvider<UserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    void requiresAuthForActuatorMetricsButNotHealth() throws Exception {
        FederationProperties properties = new FederationProperties();
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest metrics = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse metricsResponse = new MockHttpServletResponse();
        filter.doFilter(metrics, metricsResponse, new MockFilterChain());
        assertEquals(401, metricsResponse.getStatus());

        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/health")));
    }

    @Test
    void rejectsAnonymousEvenWhenEnabledFlagIsFalse() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(false);
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsMissingTokenWhenEnabled() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("demo", "demo-token");
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"detail\""));
    }

    @Test
    void acceptsConfiguredToken() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("alice", "secret");
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("alice", GatewayUser.current().name());
            assertTrue(GatewayUser.current().hasRole(GatewayRole.QUERY));
            assertTrue(!GatewayUser.current().hasRole(GatewayRole.ADMIN));
            passed.set(true);
        });
        assertTrue(passed.get());
    }

    @Test
    void respectsConfiguredRoles() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("reader", "r-token");
        properties.getSecurity().getRoles().put("reader", "QUERY");
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
        request.addHeader("Authorization", "Bearer r-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertTrue(GatewayUser.current().hasRole(GatewayRole.QUERY));
            assertTrue(!GatewayUser.current().hasRole(GatewayRole.ADMIN));
            passed.set(true);
        });
        assertTrue(passed.get());
    }

    @Test
    void acceptsJwtViaOptionalService() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        JwtAccessTokenService jwt = mock(JwtAccessTokenService.class);
        when(jwt.authenticate("aaa.bbb.ccc"))
                .thenReturn(new GatewayUser("jwt-user", Set.of(GatewayRole.QUERY)));
        @SuppressWarnings("unchecked")
        ObjectProvider<JwtAccessTokenService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jwt);
        // static looksLikeJwt is on the class; real call uses JwtAccessTokenService.looksLikeJwt
        BearerAuthFilter filter = new BearerAuthFilter(properties, provider, noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
        request.addHeader("Authorization", "Bearer aaa.bbb.ccc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("jwt-user", GatewayUser.current().name());
            passed.set(true);
        });
        assertTrue(passed.get());
    }

    @Test
    void rejectsRevokedToken() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getTokens().put("demo", "demo-token");
        TokenRevocationService revocations = new TokenRevocationService();
        revocations.revoke("demo-token");
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), revocations);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalogs");
        request.addHeader("Authorization", "Bearer demo-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("吊销"));
    }


    @Test
    void allowsBackchannelLogoutWithSharedSecret() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setBackchannelLogoutSecret("s3cret");
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/backchannel-logout");
        request.addHeader("X-Backchannel-Secret", "s3cret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> passed.set(true));
        assertTrue(passed.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void acceptsLocalSessionAndMarksRequest() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setRegistrationOpen(true);
        UserService users = new UserService(
                new MemoryUserStore(), properties, new BCryptPasswordEncoder(4), System::currentTimeMillis);
        users.bootstrapAdmin("root", "root-pass-11", null);
        String token = users.register("alice", "alice-pass-1", null).token();
        BearerAuthFilter filter = new BearerAuthFilter(
                properties, noJwt(), usersOf(users), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("alice", GatewayUser.current().name());
            assertTrue(!GatewayUser.current().hasRole(GatewayRole.ADMIN));
            assertEquals(Boolean.TRUE, req.getAttribute(BearerAuthFilter.LOCAL_ACCOUNT_ATTRIBUTE));
            passed.set(true);
        });
        assertTrue(passed.get());

        users.logout(token);
        MockHttpServletResponse afterLogout = new MockHttpServletResponse();
        filter.doFilter(request, afterLogout, new MockFilterChain());
        assertEquals(401, afterLogout.getStatus());
    }

    @Test
    void acceptsSessionCookieWhenBearerMissing() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setRegistrationOpen(true);
        UserService users = new UserService(
                new MemoryUserStore(), properties, new BCryptPasswordEncoder(4), System::currentTimeMillis);
        users.bootstrapAdmin("root", "root-pass-11", null);
        String token = users.register("carol", "carol-pass-1", null).token();
        BearerAuthFilter filter = new BearerAuthFilter(
                properties, noJwt(), usersOf(users), new TokenRevocationService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.setCookies(new jakarta.servlet.http.Cookie(SessionCookies.NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("carol", GatewayUser.current().name());
            passed.set(true);
        });
        assertTrue(passed.get());
    }

    @Test
    void registerLoginAndSetupNeedNoBearerButOtherAuthPathsDo() throws Exception {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        BearerAuthFilter filter = new BearerAuthFilter(properties, noJwt(), noUsers(), new TokenRevocationService());
        for (String path : new String[] {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/setup"}) {
            AtomicBoolean passed = new AtomicBoolean();
            filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(),
                    (req, res) -> passed.set(true));
            assertTrue(passed.get(), path);
        }
        AtomicBoolean statusOk = new AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/setup-status"), new MockHttpServletResponse(),
                (req, res) -> statusOk.set(true));
        assertTrue(statusOk.get());
        MockHttpServletResponse getLogin = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/login"), getLogin, new MockFilterChain());
        assertEquals(401, getLogin.getStatus());
        MockHttpServletResponse password = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/password"), password, new MockFilterChain());
        assertEquals(401, password.getStatus());
    }
}
