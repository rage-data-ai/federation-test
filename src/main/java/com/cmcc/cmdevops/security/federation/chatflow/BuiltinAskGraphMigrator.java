package com.cmcc.cmdevops.security.federation.chatflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;

/**
 * 将内置「智能问数」从单节点 {@code builtin_ask} 升到四阶段 DAG，并钉新 Release。
 */
final class BuiltinAskGraphMigrator {

    private static final Logger log = LoggerFactory.getLogger(BuiltinAskGraphMigrator.class);

    private BuiltinAskGraphMigrator() {
    }

    static void migrateIfNeeded(ChatflowStore store) {
        FlowDraft flow = store.findFlow(DefaultFlows.DEFAULT_FLOW_ID).orElse(null);
        if (flow == null || !DefaultFlows.isLegacyBuiltinAskGraph(flow.graph())) {
            return;
        }
        App app = store.findApp(DefaultFlows.DEFAULT_APP_ID).orElse(null);
        if (app == null) {
            return;
        }
        long now = System.currentTimeMillis();
        FlowGraph graph = DefaultFlows.builtinAsk();
        store.saveFlow(new FlowDraft(
                flow.id(), flow.name(), flow.owner(), graph, flow.status(), flow.createdAt(), now));
        int next = store.latestFlowVersion(flow.id()).map(v -> v.version() + 1).orElse(1);
        FlowVersion version = store.saveFlowVersion(new FlowVersion(
                "fv-builtin-ask-stages-" + next,
                flow.id(),
                next,
                graph,
                "system",
                now));
        AppRelease release = store.saveAppRelease(new AppRelease(
                0L, app.id(), version.id(), graph, "system", now));
        store.saveApp(new App(
                app.id(),
                app.name(),
                "默认问数应用：知识库 → 本体 → 元数据 → SQL 洞察",
                app.flowId(),
                app.owner(),
                App.PUBLISHED,
                release.id(),
                app.createdAt(),
                now));
        log.info("已将内置智能问数流程升级为四阶段 DAG flowId={} releaseId={}", flow.id(), release.id());
    }
}
