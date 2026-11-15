package com.cmcc.cmdevops.security.federation.chatflow;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.PublishedApp;

/** 问数运行入口：解析已发布应用，再交给 {@link FlowRunner}。 */
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
}
