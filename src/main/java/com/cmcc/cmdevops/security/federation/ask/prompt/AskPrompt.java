package com.cmcc.cmdevops.security.federation.ask.prompt;

import java.util.Set;

/**
 * 问数补充提示词。基础规则在 {@link com.cmcc.cmdevops.security.federation.ask.AskService} 常量里；
 * 这里存可维护的全局 / 个人补充段，compose 时接在基础规则之后（基础优先）。
 * <p>
 * 与 Hermes Agent 持久记忆的对应关系：
 * <ul>
 *   <li>{@link #GLOBAL} ↔ {@code MEMORY.md}：环境事实、口径约定、工具坑与经验（共享）</li>
 *   <li>{@link #PERSONAL} ↔ {@code USER.md}：当前用户的偏好、沟通习惯与期望</li>
 * </ul>
 *
 * @param scope {@link #GLOBAL} / {@link #PERSONAL}
 * @param owner 个人提示词所属用户；全局为空
 * @param status {@link #DRAFT} / {@link #ACTIVE} / {@link #ARCHIVED}
 * @param source {@link #HUMAN} / {@link #MODEL}
 */
public record AskPrompt(
        long id,
        String scope,
        String owner,
        String content,
        String status,
        String source,
        int version,
        String createdBy,
        String activatedBy,
        long createdAt,
        long updatedAt) {

    public static final String GLOBAL = "global";
    public static final String PERSONAL = "personal";

    public static final String DRAFT = "draft";
    public static final String ACTIVE = "active";
    public static final String ARCHIVED = "archived";

    public static final String HUMAN = "human";
    public static final String MODEL = "model";

    public static final Set<String> SCOPES = Set.of(GLOBAL, PERSONAL);
    public static final Set<String> STATUSES = Set.of(DRAFT, ACTIVE, ARCHIVED);
    public static final Set<String> SOURCES = Set.of(HUMAN, MODEL);
}
