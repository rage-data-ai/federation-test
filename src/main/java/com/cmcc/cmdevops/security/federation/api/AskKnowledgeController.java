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

import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
// AskKnowledge.KIND_* used by search default
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService.KnowledgePatch;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService.Match;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1/ask/knowledge")
@RequireRole(GatewayRole.QUERY)
public class AskKnowledgeController {

    private final AskKnowledgeService service;

    public AskKnowledgeController(AskKnowledgeService service) {
        this.service = service;
    }

    public record CreateBody(
            String question,
            String answer,
            String sql,
            List<Map<String, Object>> caliber,
            Map<String, Object> evidence,
            String kind,
            String year,
            String version) {
    }

    public record SearchBody(String question, Integer topK, String kind) {
    }

    @GetMapping
    public List<AskKnowledge> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "kind", required = false) String kind) {
        return service.list(
                status == null || status.isBlank() ? null : status,
                kind == null || kind.isBlank() ? null : kind);
    }

    @GetMapping("/{id}")
    public AskKnowledge get(@PathVariable("id") long id) {
        return service.get(id);
    }

    @PostMapping
    public AskKnowledge create(@RequestBody CreateBody body) {
        return service.create(
                body == null ? null : body.question(),
                body == null ? null : body.answer(),
                body == null ? null : body.sql(),
                body == null ? null : body.caliber(),
                body == null ? null : body.evidence(),
                body == null ? null : body.kind(),
                body == null ? null : body.year(),
                body == null ? null : body.version());
    }

    @PutMapping("/{id}")
    public AskKnowledge update(@PathVariable("id") long id, @RequestBody KnowledgePatch patch) {
        return service.update(id, patch);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.delete(id);
        return Map.of("success", true);
    }

    /** 按问题检索已通过的知识（调试与前端预览用）。 */
    @PostMapping("/search")
    public List<Match> search(@RequestBody SearchBody body) {
        int topK = body != null && body.topK() != null ? body.topK() : AskKnowledgeService.DEFAULT_TOP_K;
        String kind = body != null && body.kind() != null && !body.kind().isBlank()
                ? body.kind()
                : AskKnowledge.KIND_SMART_ASK;
        return service.search(body == null ? null : body.question(), topK, kind);
    }
}
