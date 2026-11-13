package com.cmcc.cmdevops.security.federation.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.TableRef;

/**
 * 行级过滤：配置 {@code federation.security.row-filters}
 * （用户 → {@code catalog.schema.table: predicate}）。
 * 注入时若 SQL 中该表有别名，则把谓词左侧裸列名限定为 {@code alias.column}，避免 JOIN 歧义。
 */
@Component
public class RowFilterService {

    private static final SqlParser.Config PARSER = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlConformanceEnum.LENIENT);

    private static final Pattern BARE_COLUMN = Pattern.compile(
            "(?i)^([a-z_][a-z0-9_]*)(\\s*[=<>]|\\s+is\\b|\\s+in\\b|\\s+like\\b|\\s+between\\b)");

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

    public String apply(String sql, List<TableRef> tables) {
        List<BoundRule> rules = applicable(tables);
        if (rules.isEmpty() || sql == null || sql.isBlank()) {
            return sql;
        }
        try {
            return injectWhere(sql, rules);
        } catch (Exception e) {
            return wrapSubquery(sql, rules);
        }
    }

    public void assertSatisfied(String sql, List<TableRef> tables) {
        List<BoundRule> rules = applicable(tables);
        if (rules.isEmpty() || sql == null) {
            return;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (BoundRule rule : rules) {
            String token = rule.predicate().toLowerCase(Locale.ROOT).split("[=<> ]+", 2)[0].trim();
            if (token.contains(".")) {
                token = token.substring(token.lastIndexOf('.') + 1);
            }
            if (token.isEmpty() || !lower.contains(token)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "查询缺少行级过滤条件: " + rule.table() + " 需要 " + rule.predicate());
            }
        }
    }

    /** 将裸列谓词限定为 alias.column；已带限定符则不变。 */
    static String qualifyPredicate(String predicate, String alias) {
        if (predicate == null || predicate.isBlank()) {
            return predicate;
        }
        if (alias == null || alias.isBlank()) {
            return predicate;
        }
        String trimmed = predicate.strip();
        Matcher matcher = BARE_COLUMN.matcher(trimmed);
        if (!matcher.find()) {
            return trimmed;
        }
        String column = matcher.group(1);
        if (column.contains(".")) {
            return trimmed;
        }
        return alias + "." + trimmed;
    }

    private List<BoundRule> applicable(List<TableRef> tables) {
        List<Rule> all = rulesForCurrentUser();
        if (all.isEmpty() || tables == null || tables.isEmpty()) {
            return List.of();
        }
        Map<String, Rule> byTable = new LinkedHashMap<>();
        for (Rule rule : all) {
            byTable.put(rule.table(), rule);
        }
        List<BoundRule> matched = new ArrayList<>();
        for (TableRef table : tables) {
            Rule rule = byTable.get(table.qualifiedName().toLowerCase(Locale.ROOT));
            if (rule == null) {
                continue;
            }
            String alias = table.alias();
            if (alias == null || alias.isBlank()) {
                alias = table.table();
            }
            matched.add(new BoundRule(rule.table(), qualifyPredicate(rule.predicate(), alias), alias));
        }
        return matched;
    }

    private String injectWhere(String sql, List<BoundRule> rules) throws SqlParseException {
        String text = sql.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        SqlNode root = SqlParser.create(text, PARSER).parseQuery();
        SqlSelect select = findSelect(root);
        if (select == null) {
            return wrapSubquery(sql, rules);
        }
        SqlNode where = select.getWhere();
        for (BoundRule rule : rules) {
            SqlNode pred = SqlParser.create(rule.predicate(), PARSER).parseExpression();
            where = where == null
                    ? pred
                    : SqlStdOperatorTable.AND.createCall(SqlParserPos.ZERO, where, pred);
        }
        select.setWhere(where);
        return root.toSqlString(AnsiSqlDialect.DEFAULT).getSql();
    }

    private static SqlSelect findSelect(SqlNode root) {
        if (root instanceof SqlSelect select) {
            return select;
        }
        if (root instanceof SqlOrderBy orderBy) {
            return findSelect(orderBy.query);
        }
        if (root instanceof SqlWith with) {
            return findSelect(with.body);
        }
        return null;
    }

    private static String wrapSubquery(String sql, List<BoundRule> rules) {
        // 子查询包裹后外层看不到原别名，去掉 alias. 前缀只保留列名
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            String pred = rules.get(i).predicate();
            String alias = rules.get(i).alias();
            if (alias != null && !alias.isBlank() && pred.regionMatches(true, 0, alias + ".", 0, alias.length() + 1)) {
                pred = pred.substring(alias.length() + 1);
            }
            where.append('(').append(pred).append(')');
        }
        return "SELECT * FROM (" + sql.strip() + ") AS __fed_rls WHERE " + where;
    }

    public record Rule(String table, String predicate) {
    }

    record BoundRule(String table, String predicate, String alias) {
    }
}
