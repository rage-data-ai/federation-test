package com.cmcc.cmdevops.security.federation.ask.embed;

import com.cmcc.cmdevops.security.federation.ask.embed.AskEmbedSettings.Settings;

/** 问数/知识库嵌入配置持久化（一行）。 */
public interface EmbedSettingsStore {

    Settings read();

    void write(Settings settings);
}
