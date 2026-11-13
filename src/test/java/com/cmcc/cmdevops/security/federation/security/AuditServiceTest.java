package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

class AuditServiceTest {

    private final AuditService audit = new AuditService();

    @Test
    void logsWithAndWithoutPlanAndCompactsLongSql() {
        QueryResponse response = new QueryResponse(
                true, "TRINO", "ok", List.of("a"), "SELECT 1",
                List.of(), List.of(), 1, 5, false, null);
        RoutePlan plan = new RoutePlan(EngineType.TRINO, null, Set.of("a"), "federated", "SELECT 1");

        assertDoesNotThrow(() -> audit.log("alice", "SELECT   1", plan, response));
        assertDoesNotThrow(() -> audit.log("bob", null, null, response));
        assertDoesNotThrow(() -> audit.log("carol", "x".repeat(400), plan, response));
    }
}
