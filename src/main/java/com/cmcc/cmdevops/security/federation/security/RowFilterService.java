package com.cmcc.cmdevops.security.federation.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.TableRef;

/**
 * 行级过滤：配置 {@code federation.security.row-filters}
 * （用户 → {@code catalog.schema.table: predicate}）。
 * 优先用 Calcite 将谓词 AND 进最外层 SELECT 的 WHERE；解析失败时回退为子查询包裹。
 */
@Component
public class RowFilterService {

    private static final SqlParser.Config PARSER = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlConformanceEnum.LENIENT);

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

    /** 将适用谓词注入 SQL；无规则时原样返回。 */
    public String apply(String sql, List<TableRef> tables) {
        List<Rule> rules = applicable(tables);
        if (rules.isEmpty() || sql == null || sql.isBlank()) {
            return sql;
        }
        try {
            return injectWhere(sql, rules);
        } catch (Exception e) {
            return wrapSubquery(sql, rules);
        }
    }

    /**
     * @deprecated 保留兼容测试；生产路径请用 {@link #apply} 自动注入。
     */
    public void assertSatisfied(String sql, List<TableRef> tables) {
        List<Rule> rules = applicable(tables);
        if (rules.isEmpty() || sql == null) {
            return;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            String token = rule.predicate().toLowerCase(Locale.ROOT).split("[=<> ]+", 2)[0].trim();
            if (token.isEmpty() || !lower.contains(token)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "查询缺少行级过滤条件: " + rule.table() + " 需要 " + rule.predicate());
            }
        }
    }

    private List<Rule> applicable(List<TableRef> tables) {
        List<Rule> all = rulesForCurrentUser();
        if (all.isEmpty() || tables == null || tables.isEmpty()) {
            return List.of();
        }
        Map<String, Rule> byTable = new LinkedHashMap<>();
        for (Rule rule : all) {
            byTable.put(rule.table(), rule);
        }
        List<Rule> matched = new ArrayList<>();
        for (TableRef table : tables) {
            Rule rule = byTable.get(table.qualifiedName().toLowerCase(Locale.ROOT));
            if (rule != null) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private String injectWhere(String sql, List<Rule> rules) throws SqlParseException {
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
        for (Rule rule : rules) {
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

    private static String wrapSubquery(String sql, List<Rule> rules) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            where.append('(').append(rules.get(i).predicate()).append(')');
        }
        return "SELECT * FROM (" + sql.strip() + ") AS __fed_rls WHERE " + where;
    }

    public record Rule(String table, String predicate) {
    }
}
