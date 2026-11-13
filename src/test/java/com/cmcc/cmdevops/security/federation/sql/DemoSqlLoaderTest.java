package com.cmcc.cmdevops.security.federation.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DemoSqlLoaderTest {

    @Test
    void parseSplitsNamedExamples() {
        String text = """
                -- first
                SELECT 1;

                -- second
                SELECT 2
                FROM t;

                SELECT 3;
                """;
        List<Map<String, String>> examples = DemoSqlLoader.parse(text);
        assertEquals(3, examples.size());
        assertEquals("first", examples.get(0).get("name"));
        assertEquals("SELECT 1", examples.get(0).get("sql"));
        assertEquals("second", examples.get(1).get("name"));
        assertTrue(examples.get(1).get("sql").contains("FROM t"));
        assertEquals("示例 3", examples.get(2).get("name"));
        assertEquals("SELECT 3", examples.get(2).get("sql"));
    }

    @Test
    void listLoadsClasspathOrLocalDemoSql() {
        List<Map<String, String>> examples = new DemoSqlLoader().list();
        assertFalse(examples.isEmpty());
        assertTrue(examples.stream().anyMatch(item -> item.get("sql").toUpperCase().contains("SELECT")));
    }
}
