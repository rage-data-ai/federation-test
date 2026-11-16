package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;

/** 进程内实现；构造时种下内置「智能问数」应用。 */
public class MemoryChatflowStore implements ChatflowStore {

    private final Map<String, FlowDraft> flows = new ConcurrentHashMap<>();
    private final Map<String, FlowVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, App> apps = new ConcurrentHashMap<>();
    private final Map<Long, AppRelease> releases = new ConcurrentHashMap<>();
    private final Map<String, Long> appReleaseIndex = new ConcurrentHashMap<>();
    private final AtomicLong releaseSeq = new AtomicLong(1);

    public MemoryChatflowStore() {
        seedBuiltin();
    }

    private void seedBuiltin() {
        long now = System.currentTimeMillis();
        FlowGraph graph = DefaultFlows.builtinAsk();
        FlowDraft flow = new FlowDraft(
                DefaultFlows.DEFAULT_FLOW_ID, "智能问数", "system", graph, FlowDraft.DRAFT, now, now);
        flows.put(flow.id(), flow);
        FlowVersion version = new FlowVersion("fv-builtin-ask-1", flow.id(), 1, graph, "system", now);
        versions.put(version.id(), version);
        AppRelease release = new AppRelease(releaseSeq.getAndIncrement(), DefaultFlows.DEFAULT_APP_ID, version.id(),
                graph, "system", now);
        releases.put(release.id(), release);
        appReleaseIndex.put(release.appId(), release.id());
        App app = new App(
                DefaultFlows.DEFAULT_APP_ID,
                "智能问数",
                "默认问数应用：知识库 → 本体 → 元数据 → SQL 洞察",
                flow.id(),
                "system",
                App.PUBLISHED,
                release.id(),
                now,
                now);
        apps.put(app.id(), app);
    }

    @Override
    public List<FlowDraft> listFlows() {
        return flows.values().stream()
                .sorted(Comparator.comparingLong(FlowDraft::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<FlowDraft> findFlow(String id) {
        return Optional.ofNullable(flows.get(id));
    }

    @Override
    public void saveFlow(FlowDraft flow) {
        flows.put(flow.id(), flow);
    }

    @Override
    public FlowVersion saveFlowVersion(FlowVersion version) {
        versions.put(version.id(), version);
        return version;
    }

    @Override
    public Optional<FlowVersion> findFlowVersion(String id) {
        return Optional.ofNullable(versions.get(id));
    }

    @Override
    public Optional<FlowVersion> latestFlowVersion(String flowId) {
        return versions.values().stream()
                .filter(version -> version.flowId().equals(flowId))
                .max(Comparator.comparingInt(FlowVersion::version));
    }

    @Override
    public List<App> listApps() {
        return apps.values().stream()
                .sorted(Comparator.comparingLong(App::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<App> findApp(String id) {
        return Optional.ofNullable(apps.get(id));
    }

    @Override
    public void saveApp(App app) {
        apps.put(app.id(), app);
    }

    @Override
    public AppRelease saveAppRelease(AppRelease release) {
        AppRelease stored = release.id() > 0
                ? release
                : new AppRelease(releaseSeq.getAndIncrement(), release.appId(), release.flowVersionId(),
                        release.graph(), release.publishedBy(), release.publishedAt());
        releases.put(stored.id(), stored);
        appReleaseIndex.put(stored.appId(), stored.id());
        return stored;
    }

    @Override
    public Optional<AppRelease> findAppRelease(long id) {
        return Optional.ofNullable(releases.get(id));
    }

    @Override
    public Optional<AppRelease> findAppReleaseByApp(String appId) {
        Long id = appReleaseIndex.get(appId);
        return id == null ? Optional.empty() : findAppRelease(id);
    }

    /** 测试用：清空后重新种默认应用。 */
    public void reset() {
        flows.clear();
        versions.clear();
        apps.clear();
        releases.clear();
        appReleaseIndex.clear();
        seedBuiltin();
    }

    public List<FlowVersion> listVersions(String flowId) {
        return new ArrayList<>(versions.values().stream().filter(v -> v.flowId().equals(flowId)).toList());
    }
}
