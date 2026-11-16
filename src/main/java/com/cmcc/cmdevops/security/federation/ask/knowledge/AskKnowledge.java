package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 共享知识正本。检索只对 {@link #question} 做向量召回；答案对与证据从这里取出。
 *
 * @param kind       {@link #KIND_SMART_ASK}（问数短路）或 {@link #KIND_GENERAL}（通用）
 * @param answerPair 自然语言结论 + SQL（及可选口径摘要）
 * @param evidence   口径、实体、表、结果预览、来源问数等
 * @param status     {@link #PENDING} / {@link #APPROVED} / {@link #REJECTED}
 */
public record AskKnowledge(
        long id,
        String question,
        AnswerPair answerPair,
        Map<String, Object> evidence,
        String sourceRunId,
        String kind,
        String status,
        String createdBy,
        String reviewedBy,
        String reviewNote,
        long createdAt,
        long updatedAt) {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    public static final String KIND_SMART_ASK = "smart_ask";
    public static final String KIND_GENERAL = "general";

    public static final Set<String> STATUSES = Set.of(PENDING, APPROVED, REJECTED);
    public static final Set<String> KINDS = Set.of(KIND_SMART_ASK, KIND_GENERAL);

    public record AnswerPair(String answer, String sql, List<Map<String, Object>> caliber) {
    }

    public String kindOrDefault() {
        return kind == null || kind.isBlank() ? KIND_SMART_ASK : kind;
    }
}
