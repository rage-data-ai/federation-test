package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void applyInjectsPredicateIntoWhere() {
        TableRef table = new TableRef("mysql_shop", "public", "orders", null);
        String rewritten = filters.apply(
                "SELECT id, amount FROM mysql_shop.public.orders", List.of(table));
        String lower = rewritten.toLowerCase();
        assertTrue(lower.contains("tenant_id"), rewritten);
        assertTrue(lower.contains("acme"), rewritten);
        assertTrue(lower.contains("where"), rewritten);
    }

    @Test
    void applyNoopWhenNoMatchingTable() {
        TableRef table = new TableRef("other", "public", "t", null);
        String sql = "SELECT 1 FROM other.public.t";
        assertTrue(filters.apply(sql, List.of(table)).contains("SELECT 1"));
    }

    @Test
    void adminBypassesApply() {
        GatewayUser.set(new GatewayUser("ops", Set.of(GatewayRole.ADMIN)));
        TableRef table = new TableRef("mysql_shop", "public", "orders", null);
        String sql = "SELECT * FROM mysql_shop.public.orders";
        assertFalse(filters.apply(sql, List.of(table)).toLowerCase().contains("tenant_id"));
    }

    @Test
    void qualifyBareColumnWithAlias() {
        assertEquals("o.tenant_id = 'acme'",
                RowFilterService.qualifyPredicate("tenant_id = 'acme'", "o"));
        assertEquals("o.tenant_id = 'acme'",
                RowFilterService.qualifyPredicate("o.tenant_id = 'acme'", "o"));
    }

    @Test
    void applyQualifiesPredicateUsingTableAlias() {
        TableRef table = new TableRef("mysql_shop", "public", "orders", "o");
        String rewritten = filters.apply(
                "SELECT o.id FROM mysql_shop.public.orders o JOIN mysql_shop.public.items i ON o.id = i.order_id",
                List.of(table));
        String lower = rewritten.toLowerCase();
        assertTrue(lower.contains("o.tenant_id")
                || lower.contains("`o`.`tenant_id`")
                || lower.contains("\"o\".\"tenant_id\"")
                || lower.contains("\"o\".tenant_id"), rewritten);
    }

}
