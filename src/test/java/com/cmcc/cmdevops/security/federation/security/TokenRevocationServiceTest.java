package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenRevocationServiceTest {

    @Test
    void revokeMarksToken() {
        TokenRevocationService service = new TokenRevocationService();
        assertFalse(service.isRevoked("abc"));
        service.revoke("abc");
        assertTrue(service.isRevoked("abc"));
        assertFalse(service.isRevoked("other"));
    }
}
