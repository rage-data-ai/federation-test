package com.cmcc.cmdevops.security.federation.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParser;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.TableRef;
import com.cmcc.cmdevops.security.federation.model.VectorSearchCall;

@Component
public class FederatedSqlAnalyzer {

    private static final Set<String> NOT_ALIAS = Set.of(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "WHERE", "GROUP",
            "ORDER", "LIMIT", "UNION", "EXCEPT", "INTERSECT", "HAVING", "WINDOW", "ON");

    private static final Pattern FALLBACK_TABLE = Pattern.compile(
            "(?i)(?:from|join)\\s+([a-zA-Z_][\\w]*)\\.([a-zA-Z_][\\w]*)(?:\\.([a-zA-Z_][\\w]*))?");

    private final FederationProperties properties;

    public FederatedSqlAnalyzer(FederationProperties properties) {
        this.properties = properties;
    }

    public AnalyzedQuery analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String trimmed = stripTrailingSemicolon(sql.trim());
        boolean explain = startsWithExplain(trimmed);
        String working = explain ? stripExplain(trimmed) : trimmed;

        VectorRewrite vectorRewrite = extractVectorSearches(working, properties.getVector().getDefaultK());
        List<TableRef> tables = parseTables(vectorRewrite.sql());
        return new AnalyzedQuery(sql, vectorRewrite.sql(), tables, vectorRewrite.calls(), explain);
    }

    List<TableRef> parseTables(String sql) {
        try {
            SqlParser parser = SqlParser.create(sql, SqlParser.config().withLex(Lex.MYSQL));
            List<TableRef> tables = new ArrayList<>();
            collectTables(parser.parseQuery(), tables);
            if (!tables.isEmpty()) {
                return tables;
            }
        } catch (Exception ignored) {
            // Calcite 无法解析时走 FROM/JOIN 兜底，保证路由仍可用
        }
        return fallbackTables(sql);
    }

    private void collectTables(SqlNode node, List<TableRef> tables) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlOrderBy orderBy) {
            collectTables(orderBy.query, tables);
            return;
        }
        if (node instanceof SqlWith with) {
            collectTables(with.body, tables);
            return;
        }
        if (node instanceof SqlSelect select) {
            collectFrom(select.getFrom(), tables);
            collectTables(select.getWhere(), tables);
            return;
        }
        if (node instanceof SqlBasicCall call) {
            if (call.getKind() == SqlKind.UNION || call.getKind() == SqlKind.EXCEPT || call.getKind() == SqlKind.INTERSECT) {
                call.getOperandList().forEach(operand -> collectTables(operand, tables));
            }
        }
    }

    private void collectFrom(SqlNode from, List<TableRef> tables) {
        if (from == null) {
            return;
        }
        if (from instanceof SqlJoin join) {
            collectFrom(join.getLeft(), tables);
            collectFrom(join.getRight(), tables);
            return;
        }
        if (from instanceof SqlBasicCall call && call.getKind() == SqlKind.AS) {
            SqlNode base = call.operand(0);
            String alias = identifierName(call.operand(1));
            addTable(base, alias, tables);
            return;
        }
        addTable(from, null, tables);
    }

    private void addTable(SqlNode node, String alias, List<TableRef> tables) {
        if (node instanceof SqlSelect select) {
            collectFrom(select.getFrom(), tables);
            return;
        }
        if (node instanceof SqlIdentifier id) {
            tables.add(toTableRef(id, alias));
        }
    }

    private TableRef toTableRef(SqlIdentifier id, String alias) {
        List<String> names = id.names;
        if (names.size() >= 3) {
            return new TableRef(names.get(0), names.get(1), names.get(2), alias);
        }
        if (names.size() == 2) {
            return new TableRef(names.get(0), null, names.get(1), alias);
        }
        return new TableRef(null, null, names.get(0), alias);
    }

    private List<TableRef> fallbackTables(String sql) {
        List<TableRef> tables = new ArrayList<>();
        Matcher matcher = FALLBACK_TABLE.matcher(sql);
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            String third = matcher.group(3);
            if (third != null) {
                tables.add(new TableRef(first, second, third, null));
            } else {
                tables.add(new TableRef(first, null, second, null));
            }
        }
        return tables;
    }

    static VectorRewrite extractVectorSearches(String sql, int defaultK) {
        List<VectorSearchCall> calls = new ArrayList<>();
        StringBuilder rewritten = new StringBuilder();
        String lower = sql.toLowerCase(Locale.ROOT);
        int cursor = 0;
        int index = 0;
        while (true) {
            int start = lower.indexOf("vector_search", cursor);
            if (start < 0) {
                rewritten.append(sql, cursor, sql.length());
                break;
            }
            int open = sql.indexOf('(', start);
            if (open < 0) {
                rewritten.append(sql, cursor, sql.length());
                break;
            }
            int close = matchParen(sql, open);
            AliasSpan aliasSpan = readOptionalAlias(sql, close + 1);
            String original = sql.substring(start, close + 1);
            String placeholder = "__vec_" + index;
            VectorSearchCall parsed = parseVectorCall(placeholder, original, defaultK);
            VectorSearchCall call = new VectorSearchCall(
                    parsed.placeholder(),
                    parsed.collection(),
                    parsed.field(),
                    parsed.vectorExpr(),
                    parsed.k(),
                    parsed.filter(),
                    aliasSpan.alias(),
                    sql.substring(start, aliasSpan.end()));
            calls.add(call);
            rewritten.append(sql, cursor, start);
            rewritten.append(placeholder);
            cursor = aliasSpan.end();
            index++;
        }
        return new VectorRewrite(rewritten.toString(), calls);
    }

    private static VectorSearchCall parseVectorCall(String placeholder, String original, int defaultK) {
        int open = original.indexOf('(');
        int close = original.lastIndexOf(')');
        List<String> args = splitArgs(original.substring(open + 1, close));
        if (args.size() < 3) {
            throw new IllegalArgumentException("vector_search 至少需要 collection, field, vector 三个参数");
        }
        String collection = unquote(args.get(0));
        String field = unquote(args.get(1));
        String vectorExpr = args.get(2).trim();
        int k = defaultK;
        String filter = null;
        for (int i = 3; i < args.size(); i++) {
            String arg = args.get(i).trim();
            int arrow = arg.toLowerCase(Locale.ROOT).indexOf("=>");
            if (arrow < 0) {
                continue;
            }
            String name = arg.substring(0, arrow).trim().toLowerCase(Locale.ROOT);
            String value = arg.substring(arrow + 2).trim();
            if ("k".equals(name)) {
                k = Integer.parseInt(value);
            } else if ("filter".equals(name)) {
                filter = unquote(value);
            }
        }
        return new VectorSearchCall(placeholder, collection, field, vectorExpr, k, filter, null, original);
    }

    private static List<String> splitArgs(String text) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == ']') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    args.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            args.add(current.toString().trim());
        }
        return args;
    }

    private static AliasSpan readOptionalAlias(String sql, int from) {
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i + 1 < sql.length()
                && sql.regionMatches(true, i, "AS", 0, 2)
                && !isIdentPart(sql.charAt(i + 2))) {
            i += 2;
            while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
        }
        if (i >= sql.length() || !isIdentStart(sql.charAt(i))) {
            return new AliasSpan(null, from);
        }
        int start = i;
        i++;
        while (i < sql.length() && isIdentPart(sql.charAt(i))) {
            i++;
        }
        String alias = sql.substring(start, i);
        if (NOT_ALIAS.contains(alias.toUpperCase(Locale.ROOT))) {
            return new AliasSpan(null, from);
        }
        return new AliasSpan(alias, i);
    }

    private static boolean isIdentStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isIdentPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static int matchParen(String sql, int open) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = open; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        throw new IllegalArgumentException("vector_search 括号不匹配");
    }

    private static String identifierName(SqlNode node) {
        if (node instanceof SqlIdentifier id) {
            return id.names.get(id.names.size() - 1);
        }
        return node.toString();
    }

    private static String unquote(String value) {
        String text = value.trim();
        if (text.length() >= 2
                && ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\"")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static boolean startsWithExplain(String sql) {
        return sql.regionMatches(true, 0, "EXPLAIN", 0, 7);
    }

    private static String stripExplain(String sql) {
        return sql.substring(7).trim();
    }

    private static String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
    }

    record VectorRewrite(String sql, List<VectorSearchCall> calls) {
    }

    record AliasSpan(String alias, int end) {
    }
}
