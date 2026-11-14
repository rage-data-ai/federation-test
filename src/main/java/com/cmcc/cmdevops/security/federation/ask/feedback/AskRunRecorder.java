package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.MetadataVerifier.MetadataReport;
import com.cmcc.cmdevops.security.federation.ask.MetadataVerifier.TableInfo;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.EntityHit;

/**
 * 旁路记录一次问数：事件照常转发给调用方，同时留下问题、对话历史、回答和证据。
 */
public final class AskRunRecorder implements AskListener {

    /** 结果预览只留前几行作证据，完整结果随时可以用 SQL 重跑。 */
    static final int EVIDENCE_ROWS = 20;

    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AskListener delegate;
    private final String id = UUID.randomUUID().toString();
    private final String conversationId;
    private final String userName;
    private final String question;
    private final List<Turn> history;
    private final long startedAt;

    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Object> sqls = new ArrayList<>();
    private String status;
    private String answer;
    private String sql;
    private String error;

    public AskRunRecorder(AskRequest request, String userName, AskListener delegate, long now) {
        this.delegate = delegate;
        this.userName = userName;
        this.question = request.question().strip();
        this.history = request.history() == null
                ? List.of()
                : request.history().stream().filter(turn -> turn != null && turn.content() != null).toList();
        String requested = request.conversationId();
        this.conversationId = requested != null && CONVERSATION_ID.matcher(requested).matches() ? requested : id;
        this.startedAt = now;
    }

    public String id() {
        return id;
    }

    public String conversationId() {
        return conversationId;
    }

    @Override
    public void emit(String event, Object data) {
        capture(event, data);
        delegate.emit(event, data);
    }

    /** 问数中途抛出异常时调用。 */
    public void failed(RuntimeException e) {
        status = AskRun.ERROR;
        error = e.getMessage() == null ? e.toString() : e.getMessage();
    }

    public AskRun toRun(long now) {
        if (!sqls.isEmpty()) {
            evidence.put("sqls", List.copyOf(sqls));
        }
        return new AskRun(id, conversationId, userName, question, history,
                status == null ? AskRun.ERROR : status, answer, sql, new LinkedHashMap<>(evidence),
                status == null && error == null ? "问数没有产生回答" : error,
                startedAt, Math.max(0, now - startedAt));
    }

    private void capture(String event, Object data) {
        switch (event) {
            case "ontology" -> {
                if (data instanceof Map<?, ?> payload && payload.get("context") instanceof BusinessContext context) {
                    evidence.put("entities", context.matches().stream().map(EntityHit::name).distinct().toList());
                }
            }
            case "metadata" -> {
                if (data instanceof Map<?, ?> payload && payload.get("report") instanceof MetadataReport report) {
                    evidence.put("tables", report.tables().stream().map(TableInfo::table).toList());
                }
            }
            case "sql" -> sqls.add(data);
            case "answer" -> {
                status = AskRun.ANSWERED;
                if (data instanceof Map<?, ?> payload) {
                    answer = text(payload.get("answer"));
                    sql = text(payload.get("sql"));
                    copy(payload, "caliber");
                    copy(payload, "steps");
                    copy(payload, "columns");
                    copy(payload, "rowCount");
                    if (payload.get("rows") instanceof List<?> rows) {
                        evidence.put("rows", rows.stream().limit(EVIDENCE_ROWS).toList());
                    }
                }
            }
            case "clarify" -> {
                status = AskRun.CLARIFY;
                evidence.put("clarify", data);
            }
            case "error" -> {
                status = AskRun.ERROR;
                error = data instanceof Map<?, ?> payload ? text(payload.get("message")) : text(data);
            }
            default -> {
                // 其余事件只是过程展示，不作为证据留存
            }
        }
    }

    private void copy(Map<?, ?> payload, String key) {
        Object value = payload.get(key);
        if (value != null) {
            evidence.put(key, value);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
