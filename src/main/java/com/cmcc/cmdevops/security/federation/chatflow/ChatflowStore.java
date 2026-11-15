package com.cmcc.cmdevops.security.federation.chatflow;

import java.util.List;
import java.util.Optional;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;

public interface ChatflowStore {

    List<FlowDraft> listFlows();

    Optional<FlowDraft> findFlow(String id);

    void saveFlow(FlowDraft flow);

    FlowVersion saveFlowVersion(FlowVersion version);

    Optional<FlowVersion> findFlowVersion(String id);

    Optional<FlowVersion> latestFlowVersion(String flowId);

    List<App> listApps();

    Optional<App> findApp(String id);

    void saveApp(App app);

    AppRelease saveAppRelease(AppRelease release);

    Optional<AppRelease> findAppRelease(long id);

    Optional<AppRelease> findAppReleaseByApp(String appId);
}
