package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

@Service
public class FlowService {

    private final ChatflowStore store;

    public FlowService(ChatflowStore store) {
        this.store = store;
    }

    public List<FlowDraft> list() {
        return store.listFlows();
    }

    public FlowDraft get(String id) {
        return store.findFlow(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "流程不存在：" + id));
    }

    public FlowDraft create(String name, String template) {
        String owner = GatewayUser.current().name();
        long now = System.currentTimeMillis();
        FlowGraph graph = DefaultFlows.template(template);
        FlowDraft flow = new FlowDraft(
                "flow-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                blankToDefault(name, templateTitle(template)),
                owner,
                graph,
                FlowDraft.DRAFT,
                now,
                now);
        store.saveFlow(flow);
        return flow;
    }

    public FlowDraft update(String id, String name, FlowGraph graph) {
        FlowDraft existing = get(id);
        if (graph != null) {
            graph.validate();
        }
        FlowDraft updated = new FlowDraft(
                existing.id(),
                name == null || name.isBlank() ? existing.name() : name.strip(),
                existing.owner(),
                graph == null ? existing.graph() : graph,
                existing.status(),
                existing.createdAt(),
                System.currentTimeMillis());
        store.saveFlow(updated);
        return updated;
    }

    public FlowVersion publish(String id) {
        FlowDraft flow = get(id);
        flow.graph().validate();
        int next = store.latestFlowVersion(id).map(version -> version.version() + 1).orElse(1);
        FlowVersion version = new FlowVersion(
                "fv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                flow.id(),
                next,
                flow.graph(),
                GatewayUser.current().name(),
                System.currentTimeMillis());
        return store.saveFlowVersion(version);
    }

    private static String blankToDefault(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    private static String templateTitle(String template) {
        if (DefaultFlows.TEMPLATE_LLM_CHAT.equals(template)) {
            return "简单对话";
        }
        return "智能问数";
    }
}
