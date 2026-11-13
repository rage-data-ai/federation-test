package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.SqlGuard.GuardException;
import com.cmcc.cmdevops.security.federation.ask.SqlGuard.Scope;

class SqlGuardTest {

    private static final Scope SCOPE = new Scope(
            Set.of("ck81.pangu.alert", "mysql70.tsoc.asset"),
            Map.of("ck81.pangu.alert", "occurTime"));

    @Test
    void acceptsJoinWithTimeFilterAndStripsSemicolon() {
        String sql = """
                SELECT s.name, count(*) AS cnt
                FROM ck81.pangu.alert a
                JOIN mysql70.tsoc.asset s ON a."assetId" = s.id
                WHERE a."occurTime" >= current_timestamp - INTERVAL '1' DAY AND a.level >= 3
                GROUP BY s.name ORDER BY cnt DESC LIMIT 10;
                """;
        assertTrue(SqlGuard.inspect(sql, SCOPE).endsWith("LIMIT 10"));
    }

    @Test
    void acceptsTimeFilterInsideCteAndBetween() {
        String sql = """
                WITH recent AS (
                  SELECT * FROM ck81.pangu.alert
                  WHERE occurTime BETWEEN TIMESTAMP '2026-10-01 00:00:00' AND TIMESTAMP '2026-10-02 00:00:00'
                )
                SELECT level, count(*) FROM recent GROUP BY level
                """;
        SqlGuard.inspect(sql, SCOPE);
    }

    @Test
    void rejectsLargeTableWithoutTimeFilter() {
        GuardException e = assertThrows(GuardException.class,
                () -> SqlGuard.inspect("SELECT count(*) FROM ck81.pangu.alert WHERE level >= 3", SCOPE));
        assertTrue(e.getMessage().contains("occurTime"));
    }

    @Test
    void rejectsTimeFilterOnOtherAlias() {
        String sql = """
                SELECT count(*) FROM ck81.pangu.alert a JOIN ck81.pangu.alert b ON a.id = b.id
                WHERE a.occurTime > TIMESTAMP '2026-10-01 00:00:00'
                """;
        GuardException e = assertThrows(GuardException.class, () -> SqlGuard.inspect(sql, SCOPE));
        assertTrue(e.getMessage().contains("大表"));
    }

    @Test
    void rejectsTablesOutsideScopeIncludingSubqueries() {
        String sql = """
                SELECT * FROM mysql70.tsoc.asset
                WHERE id IN (SELECT asset_id FROM mysql70.tsoc.secret)
                """;
        GuardException e = assertThrows(GuardException.class, () -> SqlGuard.inspect(sql, SCOPE));
        assertTrue(e.getMessage().contains("mysql70.tsoc.secret"));
    }

    @Test
    void rejectsUnqualifiedTableAndWrites() {
        assertTrue(assertThrows(GuardException.class, () -> SqlGuard.inspect("SELECT * FROM asset", SCOPE))
                .getMessage().contains("全限定名"));
        assertThrows(GuardException.class, () -> SqlGuard.inspect("DELETE FROM mysql70.tsoc.asset", SCOPE));
        assertThrows(GuardException.class,
                () -> SqlGuard.inspect("SELECT 1 FROM mysql70.tsoc.asset; DROP TABLE mysql70.tsoc.asset", SCOPE));
    }

    @Test
    void unionBranchesAreChecked() {
        String sql = "SELECT id FROM mysql70.tsoc.asset UNION ALL SELECT id FROM ck81.pangu.alert";
        GuardException e = assertThrows(GuardException.class, () -> SqlGuard.inspect(sql, SCOPE));
        assertEquals(1, e.getMessage().split("；").length);
    }
}
