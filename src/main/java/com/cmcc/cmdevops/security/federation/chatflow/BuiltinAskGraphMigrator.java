package com.cmcc.cmdevops.security.federation.chatflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;

/**
 * 将内置「智能问数」升级为当前默认 DAG（检索 → llm_chat 提示词约定 canAnswer → if_else → 本体/元数据 → 大模型+SQL）。
 */
final class BuiltinAskGraphMigrator {

    private static final Logger log = LoggerFactory.getLogger(BuiltinAskGraphMigrator.class);

    private BuiltinAskGraphMigrator() {
    }

    static void migrateIfNeeded(ChatflowStore store) {
        FlowDraft flow = store.findFlow(DefaultFlows.DEFAULT_FLOW_ID).orElse(null);
        if (flow == null) {
            return;
        }
        boolean legacy = DefaultFlows.isLegacyBuiltinAskGraph(flow.graph());
        boolean needUpgrade = DefaultFlows.needsBuiltinAskGraphUpgrade(flow.graph());
        if (!legacy && !needUpgrade) {
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
                "fv-builtin-ask-v" + DefaultFlows.BUILTIN_ASK_REVISION + "-" + next,
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
                "默认问数：知识检索 → 大模型(提示词 JSON/canAnswer) → 条件分流 → 本体/元数据/SQL",
                app.flowId(),
                app.owner(),
                App.PUBLISHED,
                release.id(),
                app.createdAt(),
                now));
        log.info(
                "已将内置智能问数流程升级为 llm_chat+条件 DAG flowId={} releaseId={} legacy={} upgrade={} rev={}",
                flow.id(),
                release.id(),
                legacy,
                needUpgrade,
                DefaultFlows.BUILTIN_ASK_REVISION);
    }
}
