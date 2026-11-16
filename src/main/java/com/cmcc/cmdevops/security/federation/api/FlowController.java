package com.cmcc.cmdevops.security.federation.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.chatflow.FlowGraph;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;
import com.cmcc.cmdevops.security.federation.chatflow.FlowService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1/flows")
@RequireRole(GatewayRole.QUERY)
public class FlowController {

    private final FlowService service;

    public FlowController(FlowService service) {
        this.service = service;
    }

    public record CreateBody(String name, String template) {
    }

    public record UpdateBody(String name, FlowGraph graph) {
    }

    @GetMapping
    public List<FlowDraft> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public FlowDraft get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    public FlowDraft create(@RequestBody CreateBody body) {
        return service.create(body == null ? null : body.name(), body == null ? null : body.template());
    }

    @PutMapping("/{id}")
    public FlowDraft update(@PathVariable("id") String id, @RequestBody UpdateBody body) {
        return service.update(id, body == null ? null : body.name(), body == null ? null : body.graph());
    }

    @PostMapping("/{id}/publish")
    public FlowVersion publish(@PathVariable("id") String id) {
        return service.publish(id);
    }

    @GetMapping("/templates")
    public List<Map<String, String>> templates() {
        return List.of(
                Map.of(
                        "id",
                        "builtin_ask",
                        "name",
                        "智能问数",
                        "description",
                        "知识库 → 本体 → 元数据 → SQL 洞察（四阶段 DAG）"),
                Map.of("id", "llm_chat", "name", "简单对话", "description", "仅大模型回复，不跑联邦 SQL"));
    }
}
