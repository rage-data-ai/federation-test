package com.cmcc.cmdevops.security.federation.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings;
import com.cmcc.cmdevops.security.federation.chatflow.ChatflowRuntime;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.QuotaService;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

@RestController
@RequestMapping("/api/v1")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private static final long TIMEOUT_MS = 15 * 60 * 1000L;

    private final AskService askService;
    private final ChatflowRuntime chatflowRuntime;
    private final LlmClient llmClient;
    private final AskEmbedSettings embedSettings;
    private final QuotaService quotaService;

    public AskController(
            AskService askService,
            ChatflowRuntime chatflowRuntime,
            LlmClient llmClient,
            AskEmbedSettings embedSettings,
            QuotaService quotaService) {
        this.askService = askService;
        this.chatflowRuntime = chatflowRuntime;
        this.llmClient = llmClient;
        this.embedSettings = embedSettings;
        this.quotaService = quotaService;
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
            return Map.of("success", false, "message", safeClientMessage(e));
        }
    }

    @GetMapping("/ask/embed/config")
    public Map<String, Object> embedConfig() {
        return embedSettings.view();
    }

    @RequireRole(GatewayRole.ADMIN)
    @PutMapping("/ask/embed/config")
    public Map<String, Object> updateEmbed(@RequestBody AskEmbedSettings.Settings settings) {
        return embedSettings.update(settings);
    }

    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/ask/embed/test")
    public Map<String, Object> testEmbed(@RequestBody AskEmbedSettings.Settings settings) {
        try {
            return embedSettings.test(settings);
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", safeClientMessage(e));
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
        quotaService.check(GatewayUser.current().name());
        return stream("ask-", (emitter, cancelled) -> chatflowRuntime.ask(
                request, (event, data) -> send(emitter, event, data, cancelled)));
    }

    /**
     * 编排预览：可读草稿或请求内 graph；SSE 含 node_start/node_finish；不落正式问数历史。
     */
    @RequireRole(GatewayRole.QUERY)
    @PostMapping(value = "/ask/preview", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter preview(@RequestBody ChatflowRuntime.PreviewAskRequest request) {
        quotaService.check(GatewayUser.current().name());
        return stream("ask-preview-", (emitter, cancelled) -> chatflowRuntime.preview(
                request, (event, data) -> send(emitter, event, data, cancelled)));
    }

    @FunctionalInterface
    private interface StreamBody {
        void run(SseEmitter emitter, AtomicBoolean cancelled);
    }

    private SseEmitter stream(String threadPrefix, StreamBody body) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        GatewayUser user = GatewayUser.current();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread worker = Thread.ofVirtual().name(threadPrefix, 0).unstarted(() -> {
            GatewayUser.set(user);
            try {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                body.run(emitter, cancelled);
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    log.warn("问数流失败: {}", e.getMessage(), e);
                    send(emitter, "error", Map.of("message", safeClientMessage(e)), cancelled);
                }
            } finally {
                if (!cancelled.get()) {
                    send(emitter, "done", Map.of(), cancelled);
                    try {
                        emitter.complete();
                    } catch (IllegalStateException ignored) {
                        // already completed by timeout/client
                    }
                }
                GatewayUser.clear();
            }
        });
        Runnable cancel = () -> {
            cancelled.set(true);
            worker.interrupt();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            cancel.run();
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // already completed
            }
        });
        emitter.onError(error -> cancel.run());
        worker.start();
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data, AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true);
            log.debug("SSE 客户端已断开: {}", e.getMessage());
        }
    }

    /** 对客户端可见消息脱敏：业务 IllegalArgument 原样；其它截断并避免堆栈。 */
    static String safeClientMessage(Throwable e) {
        if (e == null) {
            return "内部错误，请查看服务日志";
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                return "请求无法完成";
            }
            return message.length() > 500 ? message.substring(0, 500) + "..." : message;
        }
        if (e instanceof org.springframework.web.server.ResponseStatusException rse) {
            String reason = rse.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason.length() > 500 ? reason.substring(0, 500) + "..." : reason;
            }
        }
        return "内部错误，请查看服务日志";
    }
}
