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

import com.cmcc.cmdevops.security.federation.chatflow.AppService;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1/apps")
@RequireRole(GatewayRole.QUERY)
public class AppController {

    private final AppService service;

    public AppController(AppService service) {
        this.service = service;
    }

    public record CreateBody(String name, String description, String flowId, String template) {
    }

    public record UpdateBody(String name, String description, String flowId) {
    }

    public record PublishBody(String flowVersionId) {
    }

    @GetMapping
    public List<App> list() {
        return service.list();
    }

    @GetMapping("/published")
    public List<PublishedApp> published() {
        return service.listPublished();
    }

    @GetMapping("/{id}")
    public App get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    public App create(@RequestBody CreateBody body) {
        if (body != null && body.template() != null && !body.template().isBlank()) {
            return service.createFromTemplate(body.name(), body.description(), body.template());
        }
        return service.create(
                body == null ? null : body.name(),
                body == null ? null : body.description(),
                body == null ? null : body.flowId());
    }

    @PutMapping("/{id}")
    public App update(@PathVariable("id") String id, @RequestBody UpdateBody body) {
        return service.update(
                id,
                body == null ? null : body.name(),
                body == null ? null : body.description(),
                body == null ? null : body.flowId());
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable("id") String id, @RequestBody(required = false) PublishBody body) {
        AppRelease release = service.publish(id, body == null ? null : body.flowVersionId());
        return Map.of(
                "id", release.id(),
                "appId", release.appId(),
                "flowVersionId", release.flowVersionId(),
                "publishedAt", release.publishedAt());
    }
}
