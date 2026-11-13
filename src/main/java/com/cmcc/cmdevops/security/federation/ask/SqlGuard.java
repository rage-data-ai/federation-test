package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;

/**
 * 问数生成 SQL 的强制校验：只读、表范围限定在本体映射表内、大表必须带时间过滤、Trino 预校验。
 * 行数上限与超时由 {@link QueryExecutor} 执行时施加。
 */
@Component
public class SqlGuard {

    private static final SqlParser.Config PARSER = SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlConformanceEnum.LENIENT);

    private static final Set<SqlKind> RANGE = Set.of(
            SqlKind.GREATER_THAN, SqlKind.GREATER_THAN_OR_EQUAL,
            SqlKind.LESS_THAN, SqlKind.LESS_THAN_OR_EQUAL, SqlKind.BETWEEN);

    private final QueryExecutor executor;

    public SqlGuard(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * @param tables 允许访问的全限定表名（小写）
     * @param largeTables 大表全限定名（小写）到时间列
     */
    public record Scope(Set<String> tables, Map<String, String> largeTables) {
    }

    /**
     * 语法层检查，不访问数据库。返回规范化后的 SQL（去掉末尾分号）。
     */
    public static String inspect(String sql, Scope scope) {
        if (sql == null || sql.isBlank()) {
            throw new GuardException("SQL 为空");
        }
        String text = sql.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        SqlNode root;
        try {
            root = SqlParser.create(text, PARSER).parseQuery();
        } catch (SqlParseException e) {
            throw new GuardException("SQL 无法解析（请使用标准 SQL：双引号标识符、INTERVAL、CAST 等）：" + firstLine(e.getMessage()));
        }
        if (!root.getKind().belongsTo(SqlKind.QUERY)) {
            throw new GuardException("只允许只读查询（SELECT / WITH），实际是 " + root.getKind());
        }
        Walker walker = new Walker(scope);
        walker.query(root, Set.of());
        if (!walker.violations.isEmpty()) {
            throw new GuardException(String.join("；", walker.violations));
        }
        return text;
    }

    /**
     * 让 Trino 校验语义（表、列、类型），不真正执行。
     */
    public void validate(String sql) {
        try {
            executor.execute("EXPLAIN (TYPE VALIDATE) " + sql, 1);
        } catch (RuntimeException e) {
            throw new GuardException("Trino 预校验失败：" + firstLine(e.getMessage()));
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "未知错误";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    public static class GuardException extends RuntimeException {
        public GuardException(String message) {
            super(message);
        }
    }

    private static final class Walker {

        private final Scope scope;
        private final List<String> violations = new ArrayList<>();

        Walker(Scope scope) {
            this.scope = scope;
        }

        void query(SqlNode node, Set<String> ctes) {
            if (node == null) {
                return;
            }
            if (node instanceof SqlWith with) {
                Set<String> visible = new HashSet<>(ctes);
                for (SqlNode item : with.withList) {
                    SqlWithItem withItem = (SqlWithItem) item;
                    query(withItem.query, visible);
                    visible.add(withItem.name.getSimple().toLowerCase(Locale.ROOT));
                }
                query(with.body, visible);
                return;
            }
            if (node instanceof SqlOrderBy orderBy) {
                query(orderBy.query, ctes);
                expression(orderBy.orderList, ctes);
                return;
            }
            if (node instanceof SqlSelect select) {
                select(select, ctes);
                return;
            }
            expression(node, ctes);
        }

        private void select(SqlSelect select, Set<String> ctes) {
            List<TableUse> large = new ArrayList<>();
            List<SqlNode> filters = new ArrayList<>();
            from(select.getFrom(), ctes, large, filters);
            if (select.getWhere() != null) {
                filters.add(select.getWhere());
            }
            for (TableUse use : large) {
                if (filters.stream().noneMatch(filter -> hasRange(filter, use))) {
                    violations.add("大表 " + use.table() + " 必须在 WHERE 中按时间列 " + use.timeColumn()
                            + " 做范围过滤（如 " + use.timeColumn() + " >= current_timestamp - INTERVAL '1' DAY）");
                }
            }
            expression(select.getSelectList(), ctes);
            expression(select.getWhere(), ctes);
            expression(select.getGroup(), ctes);
            expression(select.getHaving(), ctes);
            expression(select.getOrderList(), ctes);
            expression(select.getWindowList(), ctes);
        }

        private void from(SqlNode node, Set<String> ctes, List<TableUse> large, List<SqlNode> filters) {
            if (node == null) {
                return;
            }
            if (node instanceof SqlJoin join) {
                from(join.getLeft(), ctes, large, filters);
                from(join.getRight(), ctes, large, filters);
                if (join.getCondition() != null) {
                    filters.add(join.getCondition());
                    expression(join.getCondition(), ctes);
                }
                return;
            }
            if (node instanceof SqlBasicCall call && call.getKind() == SqlKind.AS) {
                SqlNode base = call.operand(0);
                String alias = ((SqlIdentifier) call.operand(1)).getSimple();
                if (base instanceof SqlIdentifier id) {
                    table(id, alias, ctes, large);
                } else {
                    from(base, ctes, large, filters);
                }
                return;
            }
            if (node instanceof SqlIdentifier id) {
                table(id, null, ctes, large);
                return;
            }
            query(node, ctes);
        }

        private void table(SqlIdentifier id, String alias, Set<String> ctes, List<TableUse> large) {
            if (id.names.size() == 1 && ctes.contains(id.getSimple().toLowerCase(Locale.ROOT))) {
                return;
            }
            String name = String.join(".", id.names).toLowerCase(Locale.ROOT);
            if (id.names.size() != 3) {
                violations.add("表 " + name + " 必须写成 catalog.schema.table 全限定名");
                return;
            }
            if (!scope.tables().contains(name)) {
                violations.add("表 " + name + " 不在本体映射范围内，只能使用：" + String.join(", ", scope.tables()));
                return;
            }
            String timeColumn = scope.largeTables().get(name);
            if (timeColumn != null) {
                large.add(new TableUse(name, alias == null ? id.names.get(2) : alias, timeColumn));
            }
        }

        private void expression(SqlNode node, Set<String> ctes) {
            if (node == null) {
                return;
            }
            if (node instanceof SqlNodeList list) {
                list.forEach(item -> expression(item, ctes));
                return;
            }
            if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) {
                query(node, ctes);
                return;
            }
            if (node instanceof SqlCall call) {
                if (call.getKind() == SqlKind.UNION || call.getKind() == SqlKind.INTERSECT || call.getKind() == SqlKind.EXCEPT) {
                    call.getOperandList().forEach(operand -> query(operand, ctes));
                    return;
                }
                call.getOperandList().forEach(operand -> expression(operand, ctes));
            }
        }

        private static boolean hasRange(SqlNode node, TableUse use) {
            if (!(node instanceof SqlCall call)) {
                return false;
            }
            if (call.getKind() == SqlKind.AND) {
                return call.getOperandList().stream().anyMatch(operand -> hasRange(operand, use));
            }
            if (call.getKind() == SqlKind.OR) {
                return call.getOperandList().stream().allMatch(operand -> hasRange(operand, use));
            }
            if (!RANGE.contains(call.getKind())) {
                return false;
            }
            return call.getOperandList().stream().anyMatch(operand -> refersTo(operand, use));
        }

        private static boolean refersTo(SqlNode node, TableUse use) {
            if (node instanceof SqlIdentifier id) {
                String column = id.names.get(id.names.size() - 1);
                if (!column.equalsIgnoreCase(use.timeColumn())) {
                    return false;
                }
                return id.names.size() == 1 || id.names.get(id.names.size() - 2).equalsIgnoreCase(use.qualifier());
            }
            if (node instanceof SqlCall call && call.getKind() == SqlKind.CAST) {
                return refersTo(call.operand(0), use);
            }
            return false;
        }
    }

    private record TableUse(String table, String qualifier, String timeColumn) {
    }
}
