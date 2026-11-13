package com.cmcc.cmdevops.security.federation.llm;

import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;

public class MemoryLlmSettingsStore implements LlmSettingsStore {

    private Settings settings;

    @Override
    public Settings read() {
        return settings;
    }

    @Override
    public void write(Settings settings) {
        this.settings = settings;
    }
}
