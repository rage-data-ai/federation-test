package com.cmcc.cmdevops.security.federation.api;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long TIMEOUT_MS = 15 * 60 * 1000L;

    private final AskService askService;
    private final LlmClient llmClient;

    public AskController(AskService askService, LlmClient llmClient) {
        this.askService = askService;
        this.llmClient = llmClient;
    }

    @GetMapping("/llm/status")
    public Map<String, Object> llmStatus() {
        return llmClient.status();
    }

    @GetMapping("/llm/config")
    public Map<String, Object> llmConfig() {
        return llmClient.view();
    }

    @RequireRole(GatewayRole.ADMIN)
    @PutMapping("/llm/config")
    public Map<String, Object> updateLlm(@RequestBody Settings settings) {
        return llmClient.update(settings);
    }

    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/llm/test")
    public Map<String, Object> testLlm(@RequestBody Settings settings) {
        try {
            return Map.of("success", true, "message", llmClient.test(settings));
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 廉价可回答性预检（不调用 LLM、不执行 SQL）。
     * 对齐 NVIDIA Auto Ontology {@code check_answerable}：先确认本体覆盖再发起完整问数。
     */
    @RequireRole(GatewayRole.QUERY)
    @PostMapping("/ask/answerable")
    public Object answerable(@RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        return askService.answerability(request.question().strip());
    }

    @RequireRole(GatewayRole.QUERY)
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        GatewayUser user = GatewayUser.current();
        Thread.ofVirtual().name("ask-", 0).start(() -> {
            GatewayUser.set(user);
            try {
                askService.ask(request, (event, data) -> send(emitter, event, data));
            } catch (RuntimeException e) {
                log.warn("问数失败: {}", e.getMessage(), e);
                send(emitter, "error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                send(emitter, "done", Map.of());
                emitter.complete();
                GatewayUser.clear();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 客户端已断开: {}", e.getMessage());
        }
    }
}
