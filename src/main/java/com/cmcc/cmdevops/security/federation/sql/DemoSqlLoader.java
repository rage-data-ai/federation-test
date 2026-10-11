package com.cmcc.cmdevops.security.federation.sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DemoSqlLoader {

    private static final Path LOCAL_FILE = Path.of("src/main/resources/demo.sql");

    public List<Map<String, String>> list() {
        return parse(read());
    }

    private String read() {
        try {
            if (Files.exists(LOCAL_FILE)) {
                return Files.readString(LOCAL_FILE, StandardCharsets.UTF_8);
            }
            return new ClassPathResource("demo.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 demo.sql 失败: " + e.getMessage(), e);
        }
    }

    static List<Map<String, String>> parse(String text) {
        List<Map<String, String>> examples = new ArrayList<>();
        String name = null;
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                flush(examples, name, sql);
                name = trimmed.replaceFirst("^--\\s*", "");
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!sql.isEmpty()) {
                    sql.append('\n');
                }
                continue;
            }
            sql.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                flush(examples, name, sql);
                name = null;
            }
        }
        flush(examples, name, sql);
        return examples;
    }

    private static void flush(List<Map<String, String>> examples, String name, StringBuilder sql) {
        String body = sql.toString().trim();
        sql.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("name", (name == null || name.isBlank()) ? "示例 " + (examples.size() + 1) : name);
        item.put("sql", body);
        examples.add(item);
    }
}
