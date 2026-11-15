package com.cmcc.cmdevops.security.federation.ask.prompt;

import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 问数分析师可调用：把用户明确说出的偏好写成个人补充提示词（立即生效，可在「提示词」页撤销/改）。
 */
@Component
public class PreferenceTools {

    private final AskPromptService prompts;

    public PreferenceTools(AskPromptService prompts) {
        this.prompts = prompts;
    }

    @Tool("当用户明确要求记住某种问数偏好（口径、默认时间范围、回答风格等）时调用。"
            + "写入个人补充提示词并立即生效；不要用于一次性澄清。")
    public String rememberPreference(@P("要用自然语言写下的偏好，一两句即可") String preference) {
        AskPrompt saved = prompts.rememberPreference(preference);
        return "已记住为个人提示词 #" + saved.id() + "（可在提示词页撤销）";
    }
}
