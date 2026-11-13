package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtAccessTokenServiceTest {

    @Test
    void looksLikeJwt() {
        assertTrue(JwtAccessTokenService.looksLikeJwt("aaa.bbb.ccc"));
        assertFalse(JwtAccessTokenService.looksLikeJwt("demo-token"));
        assertFalse(JwtAccessTokenService.looksLikeJwt("a.b"));
    }

    @Test
    void mapsAdminRoleFromClaim() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "alice", "roles", List.of("ADMIN")));
        when(decoder.decode("token")).thenReturn(jwt);
        JwtAccessTokenService service = new JwtAccessTokenService(decoder, "roles");
        GatewayUser user = service.authenticate("token");
        assertEquals("alice", user.name());
        assertTrue(user.hasRole(GatewayRole.ADMIN));
        assertTrue(user.hasRole(GatewayRole.QUERY));
    }

    @Test
    void returnsNullOnInvalidJwt() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("bad")).thenThrow(new JwtException("nope"));
        assertNull(new JwtAccessTokenService(decoder, "roles").authenticate("bad"));
    }
}
