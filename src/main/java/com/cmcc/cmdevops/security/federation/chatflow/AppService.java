package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

@Service
public class AppService {

    private final ChatflowStore store;

    public AppService(ChatflowStore store) {
        this.store = store;
    }

    public List<App> list() {
        return store.listApps();
    }

    public List<PublishedApp> listPublished() {
        return store.listApps().stream()
                .filter(app -> App.PUBLISHED.equals(app.status()) && app.currentReleaseId() != null)
                .map(app -> resolvePublished(app.id()))
                .toList();
    }

    public App get(String id) {
        return store.findApp(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在：" + id));
    }

    public App create(String name, String description, String flowId) {
        FlowDraft flow = store.findFlow(flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在：" + flowId));
        long now = System.currentTimeMillis();
        App app = new App(
                "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                blank(name, flow.name()),
                description == null ? "" : description.strip(),
                flow.id(),
                GatewayUser.current().name(),
                App.DRAFT,
                null,
                now,
                now);
        store.saveApp(app);
        return app;
    }

    /** 从模板一步创建流程 + 应用（仍为草稿，需 publish）。 */
    public App createFromTemplate(String name, String description, String template) {
        String owner = GatewayUser.current().name();
        long now = System.currentTimeMillis();
        FlowGraph graph = DefaultFlows.template(template);
        FlowDraft flow = new FlowDraft(
                "flow-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                blank(name, DefaultFlows.TEMPLATE_LLM_CHAT.equals(template) ? "简单对话" : "智能问数"),
                owner,
                graph,
                FlowDraft.DRAFT,
                now,
                now);
        store.saveFlow(flow);
        App app = new App(
                "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                blank(name, flow.name()),
                description == null ? "" : description.strip(),
                flow.id(),
                owner,
                App.DRAFT,
                null,
                now,
                now);
        store.saveApp(app);
        return app;
    }

    public App update(String id, String name, String description, String flowId) {
        App existing = get(id);
        if (flowId != null && !flowId.isBlank() && !flowId.equals(existing.flowId())) {
            store.findFlow(flowId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在：" + flowId));
        }
        App updated = new App(
                existing.id(),
                name == null || name.isBlank() ? existing.name() : name.strip(),
                description == null ? existing.description() : description.strip(),
                flowId == null || flowId.isBlank() ? existing.flowId() : flowId.strip(),
                existing.owner(),
                existing.status(),
                existing.currentReleaseId(),
                existing.createdAt(),
                System.currentTimeMillis());
        store.saveApp(updated);
        return updated;
    }

    /**
     * 发布应用：若未指定 flowVersionId，则把当前流程草稿打成新版本再钉上。
     */
    public AppRelease publish(String appId, String flowVersionId) {
        App app = get(appId);
        FlowVersion version;
        if (flowVersionId == null || flowVersionId.isBlank()) {
            FlowDraft flow = store.findFlow(app.flowId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在：" + app.flowId()));
            flow.graph().validate();
            int next = store.latestFlowVersion(flow.id()).map(v -> v.version() + 1).orElse(1);
            version = new FlowVersion(
                    "fv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    flow.id(),
                    next,
                    flow.graph(),
                    GatewayUser.current().name(),
                    System.currentTimeMillis());
            store.saveFlowVersion(version);
        } else {
            version = store.findFlowVersion(flowVersionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "流程版本不存在：" + flowVersionId));
            if (!version.flowId().equals(app.flowId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流程版本不属于该应用绑定的流程");
            }
        }
        AppRelease release = store.saveAppRelease(new AppRelease(
                0L,
                app.id(),
                version.id(),
                version.graph(),
                GatewayUser.current().name(),
                System.currentTimeMillis()));
        App published = new App(
                app.id(),
                app.name(),
                app.description(),
                app.flowId(),
                app.owner(),
                App.PUBLISHED,
                release.id(),
                app.createdAt(),
                System.currentTimeMillis());
        store.saveApp(published);
        return release;
    }

    public PublishedApp resolvePublished(String appId) {
        String id = appId == null || appId.isBlank() ? DefaultFlows.DEFAULT_APP_ID : appId.strip();
        App app = get(id);
        if (!App.PUBLISHED.equals(app.status()) || app.currentReleaseId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "应用尚未发布：" + id);
        }
        AppRelease release = store.findAppRelease(app.currentReleaseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "应用发布记录缺失：" + id));
        return new PublishedApp(app.id(), app.name(), release.id(), release.flowVersionId(), release.graph());
    }

    private static String blank(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }
}
