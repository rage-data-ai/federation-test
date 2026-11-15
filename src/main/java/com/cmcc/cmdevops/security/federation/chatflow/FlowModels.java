package com.cmcc.cmdevops.security.federation.chatflow;

/**
 * 流程与应用的持久化模型（草稿 / 版本 / 应用 / 发布）。
 */
public final class FlowModels {

    private FlowModels() {
    }

    public record FlowDraft(
            String id,
            String name,
            String owner,
            FlowGraph graph,
            String status,
            long createdAt,
            long updatedAt) {
        public static final String DRAFT = "draft";
        public static final String ARCHIVED = "archived";
    }

    public record FlowVersion(
            String id,
            String flowId,
            int version,
            FlowGraph graph,
            String publishedBy,
            long publishedAt) {
    }

    public record App(
            String id,
            String name,
            String description,
            String flowId,
            String owner,
            String status,
            Long currentReleaseId,
            long createdAt,
            long updatedAt) {
        public static final String DRAFT = "draft";
        public static final String PUBLISHED = "published";
        public static final String ARCHIVED = "archived";
    }

    public record AppRelease(
            long id,
            String appId,
            String flowVersionId,
            FlowGraph graph,
            String publishedBy,
            long publishedAt) {
    }

    /** 运行时解析结果：可执行的发布图。 */
    public record PublishedApp(String appId, String appName, long releaseId, String flowVersionId, FlowGraph graph) {
    }
}
