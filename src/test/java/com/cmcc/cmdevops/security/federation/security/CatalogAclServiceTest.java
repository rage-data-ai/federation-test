package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class CatalogAclServiceTest {

    private FederationProperties properties;
    private CatalogAclService acl;

    @BeforeEach
    void setUp() {
        properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setCatalogs(Map.of(
                "reader", "mysql_shop, clickhouse",
                "demo", "*"));
        acl = new CatalogAclService(properties);
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void readerLimitedToGrantedCatalogs() {
        GatewayUser.set(new GatewayUser("reader", Set.of(GatewayRole.QUERY)));
        assertTrue(acl.allows("mysql_shop"));
        assertTrue(acl.allows("CLICKHOUSE"));
        assertFalse(acl.allows("secret_db"));
        assertThrows(ResponseStatusException.class, () -> acl.assertAllowed(List.of("secret_db")));
        assertEquals(List.of("mysql_shop"), acl.filter(List.of("mysql_shop", "secret_db"), name -> name));
    }

    @Test
    void adminBypassesAcl() {
        GatewayUser.set(new GatewayUser("ops", Set.of(GatewayRole.ADMIN)));
        assertTrue(acl.allows("anything"));
        assertEquals(Set.of("*"), acl.currentGrants());
    }

    @Test
    void disabledWhenCatalogMapEmpty() {
        properties.getSecurity().setCatalogs(Map.of());
        GatewayUser.set(new GatewayUser("reader", Set.of(GatewayRole.QUERY)));
        assertFalse(acl.isEnforced());
        assertTrue(acl.allows("secret_db"));
    }
}
