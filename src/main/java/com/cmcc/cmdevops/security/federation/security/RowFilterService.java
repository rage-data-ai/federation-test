package com.cmcc.cmdevops.security.federation.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.TableRef;

/**
 * 行级过滤配置读取器。配置 {@code federation.security.row-filters}：
 * 用户 → 逗号分隔规则 {@code catalog.schema.table: SQL谓词}。
 * 查询路径以「SQL 文本须包含谓词列标识」做保守校验；AST 自动注入留待后续。
 */
@Component
public class RowFilterService {

    private final FederationProperties properties;

    public RowFilterService(FederationProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.getSecurity().isEnabled()
                && !properties.getSecurity().getRowFilters().isEmpty();
    }

    public List<Rule> rulesForCurrentUser() {
        if (!isConfigured()) {
            return List.of();
        }
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return List.of();
        }
        String raw = properties.getSecurity().getRowFilters().get(user.name());
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int colon = item.indexOf(':');
            if (colon <= 0 || colon == item.length() - 1) {
                continue;
            }
            String table = item.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String predicate = item.substring(colon + 1).trim();
            if (!table.isEmpty() && !predicate.isEmpty()) {
                rules.add(new Rule(table, predicate));
            }
        }
        return rules;
    }

    public void assertSatisfied(String sql, List<TableRef> tables) {
        List<Rule> rules = rulesForCurrentUser();
        if (rules.isEmpty() || sql == null || tables == null || tables.isEmpty()) {
            return;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        Map<String, Rule> byTable = new LinkedHashMap<>();
        for (Rule rule : rules) {
            byTable.put(rule.table(), rule);
        }
        for (TableRef table : tables) {
            Rule rule = byTable.get(table.qualifiedName().toLowerCase(Locale.ROOT));
            if (rule == null) {
                continue;
            }
            String needle = rule.predicate().toLowerCase(Locale.ROOT);
            String token = needle.split("[=<> ]+", 2)[0].trim();
            if (token.isEmpty() || !lower.contains(token)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "查询缺少行级过滤条件: " + rule.table() + " 需要 " + rule.predicate());
            }
        }
    }

    public record Rule(String table, String predicate) {
    }
}
