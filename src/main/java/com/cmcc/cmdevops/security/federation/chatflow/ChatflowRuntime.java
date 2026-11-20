package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;

/** 问数运行入口：生产读已发布应用；预览可读草稿/脏图。 */
@Service
public class ChatflowRuntime {

    private final AppService apps;
    private final FlowRunner runner;

    public ChatflowRuntime(AppService apps, FlowRunner runner) {
        this.apps = apps;
        this.runner = runner;
    }

    public void ask(AskRequest request, AskListener listener) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        PublishedApp app = apps.resolvePublished(request.appId());
        runner.run(app, request, listener);
    }

    /**
     * 编排预览：{@code graph} 非空时用请求图，否则用应用当前草稿。
     */
    public void preview(PreviewAskRequest request, AskListener listener) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        PublishedApp app = apps.resolveForPreview(request.appId(), request.graph());
        AskRequest ask = new AskRequest(
                request.question().strip(),
                request.history() == null ? List.of() : request.history(),
                request.conversationId(),
                app.appId());
        runner.runPreview(app, ask, listener);
    }

    /** 预览请求：可带画布脏图。 */
    public record PreviewAskRequest(
            String question,
            List<Turn> history,
            String conversationId,
            String appId,
            FlowGraph graph) {
    }
}
