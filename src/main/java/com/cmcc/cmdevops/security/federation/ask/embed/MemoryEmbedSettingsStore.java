package com.cmcc.cmdevops.security.federation.ask.embed;

import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings.Settings;

public class MemoryEmbedSettingsStore implements EmbedSettingsStore {

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
