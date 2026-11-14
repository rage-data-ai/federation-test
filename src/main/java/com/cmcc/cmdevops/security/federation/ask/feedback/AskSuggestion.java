package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.Set;

/**
 * 用户对问数提出的建议。
 *
 * @param runId       针对的问数记录，可为空
 * @param runQuestion 该问数记录的问题，仅用于展示
 * @param status      {@link #OPEN} / {@link #ADOPTED} / {@link #REJECTED}
 * @param disposition 采纳后的去向：{@code knowledge} / {@code prompt} / {@code ontology}，可为空
 * @param reply       处理人的答复
 */
public record AskSuggestion(
        long id,
        String runId,
        String runQuestion,
        String userName,
        String content,
        String status,
        String disposition,
        String reply,
        String handledBy,
        long createdAt,
        long updatedAt) {

    public static final String OPEN = "open";
    public static final String ADOPTED = "adopted";
    public static final String REJECTED = "rejected";

    public static final Set<String> STATUSES = Set.of(OPEN, ADOPTED, REJECTED);
    public static final Set<String> DISPOSITIONS = Set.of("knowledge", "prompt", "ontology");
}
