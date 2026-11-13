package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.cmcc.cmdevops.security.federation.ask.MetadataVerifier.MetadataReport;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.EntityHit;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.ObjectMapper;

/**
 * DataInsight 智能体单次请求的工具与预算：SQL 经 {@link SqlGuard} 校验后执行；
 * 本体恢复、元数据恢复各 1 次；出现退化结果后只再允许 1 次诊断 SQL。
 */
final class InsightSession {

    static final int MAX_SQL_ATTEMPTS = 8;

    private final String question;
    private final OntologyTools ontology;
    private final MetadataVerifier verifier;
    private final Function<String, QueryResponse> runner;
    private final Consumer<String> validator;
    private final AskListener listener;
    private final ObjectMapper objectMapper;
    private final int previewRows;

    private BusinessContext context;
    private MetadataReport report;
    private boolean ontologyRecovered;
    private boolean metadataRecovered;
    private int attempts;
    private String degenerate;
    private int afterDegenerate;
    private int filterValidations;
    private final Map<String, QueryResponse> results = new LinkedHashMap<>();

    InsightSession(
            String question,
            OntologyTools ontology,
            MetadataVerifier verifier,
            BusinessContext context,
            MetadataReport report,
            boolean metadataRecovered,
            Function<String, QueryResponse> runner,
            Consumer<String> validator,
            AskListener listener,
            ObjectMapper objectMapper,
            int previewRows) {
        this.question = question;
        this.ontology = ontology;
        this.verifier = verifier;
        this.context = context;
        this.report = report;
        this.metadataRecovered = metadataRecovered;
        this.runner = runner;
        this.validator = validator;
        this.listener = listener;
        this.objectMapper = objectMapper;
        this.previewRows = previewRows;
    }

    @Tool("校验过滤值是否与本体码表一致。传入属性名或物理列名及拟写入 WHERE 的值；"
            + "若命中中文标签会返回 suggestedValue（应改用码值 value）")
    public String validateFilterValues(
            @P("属性名或物理列名，例如 alertLevel 或 level") String attributeOrColumn,
            @P("拟使用的过滤值") String value) {
        filterValidations++;
        Map<String, Object> result = FilterValueValidator.validate(context, attributeOrColumn, value);
        listener.emit("filter_check", result);
        return objectMapper.writeValueAsString(result);
    }

    @Tool("扫描 SQL 中的字符串字面量，对照本体码表给出修正建议；结果为空或怀疑用了中文名过滤时调用")
    public String scanFilterLiterals(@P("待检查的完整 SQL") String sql) {
        filterValidations++;
        List<Map<String, Object>> findings = FilterValueValidator.scanSqlLiterals(context, sql);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findings", findings);
        result.put("ok", findings.isEmpty());
        listener.emit("filter_check", result);
        return objectMapper.writeValueAsString(result);
    }

    @Tool("执行一条只读 Trino SQL 并返回结果预览。SQL 会先经过只读、表范围、大表时间过滤和 Trino 预校验，"
            + "不通过时返回原因，请据此修改后重试")
    public String runSql(
            @P("完整的 SQL，表名写 catalog.schema.table") String sql,
            @P("用途：main 表示回答问题的主查询，diagnostic 表示排查退化结果的诊断查询") String purpose) {
        if (attempts >= MAX_SQL_ATTEMPTS) {
            return "已达到本次请求的 SQL 次数上限（" + MAX_SQL_ATTEMPTS + " 次），请基于已有结果作答";
        }
        if (degenerate != null && afterDegenerate >= 1) {
            return "退化结果已追加过 1 次诊断 SQL，不再执行新的 SQL。请直接作答，并说明结果" + degenerate + "的可能原因";
        }
        attempts++;
        String normalized;
        try {
            normalized = SqlGuard.inspect(sql, report.scope());
            validator.accept(normalized);
        } catch (SqlGuard.GuardException e) {
            listener.emit("sql", sqlEvent(sql, purpose, "rejected", e.getMessage()));
            return "校验未通过：" + e.getMessage();
        }
        QueryResponse response;
        try {
            response = runner.apply(normalized);
        } catch (RuntimeException e) {
            listener.emit("sql", sqlEvent(normalized, purpose, "failed", e.getMessage()));
            return "执行失败：" + e.getMessage();
        }
        listener.emit("sql", sqlEvent(normalized, purpose, "executed", null));
        if (degenerate != null) {
            afterDegenerate++;
        }
        results.put(key(normalized), response);
        String shape = degenerateShape(response.rows());
        if (shape != null && degenerate == null) {
            degenerate = shape;
        }
        List<Map<String, Object>> literalHints = List.of();
        if ("为空".equals(shape) || "全为 0".equals(shape)) {
            literalHints = FilterValueValidator.scanSqlLiterals(context, normalized);
        }
        List<List<Object>> preview = preview(response.rows());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sql", normalized);
        event.put("purpose", purpose);
        event.put("columns", response.columns());
        event.put("rows", preview);
        event.put("rowCount", response.rowCount());
        event.put("elapsedMs", response.elapsedMs());
        event.put("degenerate", shape);
        if (!literalHints.isEmpty()) {
            event.put("filterHints", literalHints);
        }
        listener.emit("result", event);

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("columns", response.columns().stream().map(QueryResponse.ColumnMeta::name).toList());
        reply.put("rows", preview);
        reply.put("rowCount", response.rowCount());
        reply.put("truncated", response.rowCount() > preview.size());
        if (shape != null) {
            reply.put("degenerate", shape);
            reply.put("hint", afterDegenerate == 0 && degenerate.equals(shape)
                    ? "结果是退化形态，可以追加 1 次 diagnostic SQL 排查（如去掉部分条件看分布、核对码值），然后作答"
                    : "请直接作答并说明结果" + shape + "的可能原因");
        }
        if (!literalHints.isEmpty()) {
            reply.put("filterHints", literalHints);
            reply.put("filterHint", "SQL 字面量可能与码表不一致，请按 filterHints 修正 value 后重试");
        }
        return objectMapper.writeValueAsString(reply);
    }

    int filterValidations() {
        return filterValidations;
    }

    @Tool("本体恢复（每次请求最多 1 次）：业务上下文缺少需要的概念、属性或关系时，用业务词补充检索本体，返回新的业务上下文和元数据")
    public String recoverOntology(@P("需要补充检索的业务词，例如 [\"资产\", \"漏洞等级\"]") List<String> keywords) {
        if (ontologyRecovered) {
            return "本体恢复本次请求已用过，不能再次调用。请基于现有上下文作答，或用 clarify 向用户确认";
        }
        ontologyRecovered = true;
        Set<String> entities = new LinkedHashSet<>();
        List<String> missed = new ArrayList<>();
        for (String keyword : keywords == null ? List.<String>of() : keywords) {
            List<EntityHit> hits = ontology.searchEntities(keyword);
            if (hits.isEmpty() || hits.get(0).score() < 0.5) {
                missed.add(keyword);
            } else {
                entities.add(hits.get(0).name());
            }
        }
        context.matches().forEach(hit -> entities.add(hit.name()));
        context = ontology.businessContext(question, entities);
        report = verifier.verify(context, false);
        listener.emit("ontology", Map.of("recovery", true, "context", context));
        listener.emit("metadata", Map.of("recovery", true, "report", report));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("notFoundInOntology", missed);
        reply.put("context", context);
        reply.put("metadata", report);
        return objectMapper.writeValueAsString(reply);
    }

    @Tool("元数据恢复（每次请求最多 1 次）：Trino 报表或列不存在，或元数据缺表时，重新采集业务上下文中已映射的表，返回新的元数据")
    public String recoverMetadata() {
        if (metadataRecovered) {
            return "元数据恢复本次请求已用过，不能再次调用。请基于现有元数据作答，或说明缺失的表";
        }
        metadataRecovered = true;
        report = verifier.verify(context, true);
        listener.emit("metadata", Map.of("recovery", true, "report", report));
        return objectMapper.writeValueAsString(report);
    }

    BusinessContext context() {
        return context;
    }

    MetadataReport report() {
        return report;
    }

    /**
     * 最终展示的结果：优先匹配回答里给出的 SQL，否则取最后一次主查询。
     */
    QueryResponse resultFor(String sql) {
        if (sql != null) {
            QueryResponse exact = results.get(key(sql.strip().replaceAll(";+$", "")));
            if (exact != null) {
                return exact;
            }
        }
        List<QueryResponse> all = new ArrayList<>(results.values());
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    List<List<Object>> preview(List<List<Object>> rows) {
        return rows.stream().limit(previewRows)
                .map(row -> row.stream().map(InsightSession::cell).toList())
                .toList();
    }

    static String degenerateShape(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return "为空";
        }
        List<Object> cells = rows.stream().flatMap(List::stream).toList();
        if (cells.stream().allMatch(Objects::isNull)) {
            return "全为 NULL";
        }
        if (cells.stream().allMatch(cell -> cell instanceof Number number && number.doubleValue() == 0)) {
            return "全为 0";
        }
        if (rows.size() > 1 && rows.stream().distinct().count() == 1) {
            return "各行完全相同";
        }
        return null;
    }

    private static Object cell(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    private static String key(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private static Map<String, Object> sqlEvent(String sql, String purpose, String status, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sql", sql);
        event.put("purpose", purpose);
        event.put("status", status);
        event.put("message", message);
        return event;
    }
}
