package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class QuotaServiceTest {

    @Test
    void enforcesPerMinuteLimit() {
        FederationProperties properties = new FederationProperties();
        properties.getQuota().setMaxRequestsPerMinute(2);
        QuotaService quota = new QuotaService(properties);
        assertDoesNotThrow(() -> quota.check("u1"));
        assertDoesNotThrow(() -> quota.check("u1"));
        assertThrows(ResponseStatusException.class, () -> quota.check("u1"));
        assertDoesNotThrow(() -> quota.check("u2"));
    }

    @Test
    void disabledWhenLimitNonPositive() {
        FederationProperties properties = new FederationProperties();
        properties.getQuota().setMaxRequestsPerMinute(0);
        QuotaService quota = new QuotaService(properties);
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> quota.check("u"));
        }
    }
}
