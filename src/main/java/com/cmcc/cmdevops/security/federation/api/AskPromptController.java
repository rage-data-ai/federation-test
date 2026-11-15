package com.cmcc.cmdevops.security.federation.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.ask.prompt.AskPrompt;
import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService;
import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService.PromptPatch;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1/ask/prompts")
@RequireRole(GatewayRole.QUERY)
public class AskPromptController {

    private final AskPromptService service;

    public AskPromptController(AskPromptService service) {
        this.service = service;
    }

    public record CreateBody(String scope, String content, String source) {
    }

    @GetMapping
    public List<AskPrompt> list(@RequestParam(name = "scope", required = false) String scope) {
        return service.list(scope);
    }

    @GetMapping("/{id}")
    public AskPrompt get(@PathVariable("id") long id) {
        return service.get(id);
    }

    @PostMapping
    public AskPrompt create(@RequestBody CreateBody body) {
        return service.create(
                body == null ? null : body.scope(),
                body == null ? null : body.content(),
                body == null ? null : body.source());
    }

    @PutMapping("/{id}")
    public AskPrompt update(@PathVariable("id") long id, @RequestBody PromptPatch patch) {
        return service.update(id, patch);
    }

    @PostMapping("/{id}/activate")
    public AskPrompt activate(@PathVariable("id") long id) {
        return service.activate(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.delete(id);
        return Map.of("success", true);
    }

    /** 管理员：根据开放建议生成全局草稿提示词。 */
    @PostMapping("/proposals")
    @RequireRole(GatewayRole.ADMIN)
    public AskPrompt propose() {
        return service.proposeFromFeedback();
    }
}
