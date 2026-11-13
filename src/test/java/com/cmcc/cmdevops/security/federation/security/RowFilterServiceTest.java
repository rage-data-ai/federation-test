package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.TableRef;

class RowFilterServiceTest {

    private RowFilterService filters;

    @BeforeEach
    void setUp() {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setRowFilters(Map.of(
                "reader", "mysql_shop.public.orders:tenant_id = 'acme'"));
        filters = new RowFilterService(properties);
        GatewayUser.set(new GatewayUser("reader", Set.of(GatewayRole.QUERY)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void rejectsSqlMissingPredicateToken() {
        TableRef table = new TableRef("mysql_shop", "public", "orders", null);
        assertThrows(ResponseStatusException.class,
                () -> filters.assertSatisfied("SELECT * FROM mysql_shop.public.orders", List.of(table)));
    }

    @Test
    void acceptsSqlContainingPredicateToken() {
        TableRef table = new TableRef("mysql_shop", "public", "orders", null);
        assertDoesNotThrow(() -> filters.assertSatisfied(
                "SELECT * FROM mysql_shop.public.orders WHERE tenant_id = 'acme'", List.of(table)));
    }
}
