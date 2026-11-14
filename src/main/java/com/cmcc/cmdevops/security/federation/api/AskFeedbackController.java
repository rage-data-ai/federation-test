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

import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackService;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackService.RunView;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackService.SuggestionPatch;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskSuggestion;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

/**
 * 问数记录、评价和建议。谁能看、谁能改由 {@link AskFeedbackService} 判定，这里只要求能问数的角色。
 */
@RestController
@RequestMapping("/api/v1/ask")
@RequireRole(GatewayRole.QUERY)
public class AskFeedbackController {

    private final AskFeedbackService service;

    public AskFeedbackController(AskFeedbackService service) {
        this.service = service;
    }

    public record RatingBody(String rating) {
    }

    public record SuggestionBody(String content, String runId) {
    }

    @GetMapping("/runs/{id}")
    public RunView run(@PathVariable("id") String id) {
        return service.run(id);
    }

    /** body {"rating": "up" | "down" | null}，null 表示撤销评价。 */
    @PutMapping("/runs/{id}/rating")
    public RunView rate(@PathVariable("id") String id, @RequestBody RatingBody body) {
        return service.rate(id, body == null ? null : body.rating());
    }

    @GetMapping("/suggestions")
    public List<AskSuggestion> suggestions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "mine", defaultValue = "false") boolean mine) {
        return service.suggestions(status == null || status.isBlank() ? null : status, mine);
    }

    @PostMapping("/suggestions")
    public AskSuggestion suggest(@RequestBody SuggestionBody body) {
        return service.suggest(body == null ? null : body.content(), body == null ? null : body.runId());
    }

    @PutMapping("/suggestions/{id}")
    public AskSuggestion update(@PathVariable("id") long id, @RequestBody SuggestionPatch patch) {
        return service.update(id, patch);
    }

    @DeleteMapping("/suggestions/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        service.delete(id);
        return Map.of("success", true);
    }
}
