package com.cmcc.cmdevops.security.federation.llm;

import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;

/** 大模型配置的持久化。一行、按列存放。 */
public interface LlmSettingsStore {

    Settings read();

    void write(Settings settings);
}
