package com.cmcc.cmdevops.security.federation.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final FederationProperties.Llm config;
    private final LlmSettingsStore settingsStore;
    private volatile ChatModel model;

    @Autowired
    public LlmClient(FederationProperties properties, LlmSettingsStore settingsStore) {
        this(properties.getLlm(), settingsStore);
    }

    LlmClient(FederationProperties.Llm config, LlmSettingsStore settingsStore) {
        this.config = config;
        this.settingsStore = settingsStore;
    }

    private LlmClient(FederationProperties.Llm config, ChatModel model) {
        this.config = config;
        this.settingsStore = null;
        this.model = model;
    }

    /** 使用现成的模型实例，不读取配置。 */
    public static LlmClient of(ChatModel model) {
        FederationProperties.Llm config = new FederationProperties.Llm();
        config.setProtocol("custom");
        config.setBaseUrl("custom");
        config.setModel(model.getClass().getSimpleName());
        return new LlmClient(config, model);
    }

    @PostConstruct
    public void init() {
        if (settingsStore == null) {
            return;
        }
        Settings saved = settingsStore.read();
        if (saved == null) {
            return;
        }
        apply(saved, true);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", config.isConfigured());
        status.put("protocol", config.getProtocol());
        status.put("baseUrl", config.getBaseUrl());
        status.put("model", config.getModel());
        return status;
    }

    /** 给配置界面用，不返回密钥本身。 */
    public synchronized Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>(status());
        view.put("apiKeySet", !config.getApiKey().isBlank());
        return view;
    }

    public synchronized Map<String, Object> update(Settings settings) {
        apply(settings, false);
        model = null;
        write();
        return view();
    }

    /** 用当前填写的值试一次调用，不保存。密钥留空时沿用已保存的密钥。 */
    public String test(Settings settings) {
        FederationProperties.Llm trial = copy(config);
        fill(trial, settings, false);
        requireConfigured(trial);
        try {
            String reply = build(trial).chat(SystemMessage.from("只回复 ok"), UserMessage.from("ping")).aiMessage().text();
            return reply == null ? "" : reply.strip();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(AnthropicResponseGuard.explain(e), e);
        }
    }

    public ChatModel model() {
        if (!config.isConfigured()) {
            throw new IllegalStateException("未配置大模型：请点左侧「模型」填写协议、接口地址和模型名");
        }
        ChatModel current = model;
        if (current == null) {
            synchronized (this) {
                if (model == null) {
                    model = build(config);
                }
                current = model;
            }
        }
        return current;
    }

    public String complete(String system, String user) {
        try {
            ChatModel chat = model();
            String reply = replyText(chat.chat(SystemMessage.from(system), UserMessage.from(user)).aiMessage());
            if (reply == null || reply.isBlank()) {
                reply = replyText(chat.chat(SystemMessage.from(system), UserMessage.from(user)).aiMessage());
            }
            return reply;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(AnthropicResponseGuard.explain(e), e);
        }
    }

    /** 正式回答为空时，使用思考内容里带 JSON 的部分。思考模型常把正文留空。 */
    static String replyText(AiMessage message) {
        if (message == null) {
            return null;
        }
        String text = message.text();
        if (text != null && !text.isBlank()) {
            return text;
        }
        String thinking = message.thinking();
        if (thinking != null && thinking.indexOf('{') >= 0) {
            log.info("大模型正式回答为空，改用思考内容中的 JSON");
            return thinking;
        }
        return text;
    }

    private void apply(Settings settings, boolean fromFile) {
        fill(config, settings, fromFile);
    }

    private static void fill(FederationProperties.Llm target, Settings settings, boolean fromFile) {
        String protocol = settings.protocol() == null || settings.protocol().isBlank()
                ? "openai" : settings.protocol().strip().toLowerCase(Locale.ROOT);
        if (!protocol.equals("openai") && !protocol.equals("anthropic")) {
            throw new IllegalArgumentException("协议只支持 openai 或 anthropic");
        }
        target.setProtocol(protocol);
        target.setBaseUrl(settings.baseUrl() == null ? "" : settings.baseUrl().strip());
        target.setModel(settings.model() == null ? "" : settings.model().strip());
        if (fromFile || (settings.apiKey() != null && !settings.apiKey().isBlank())) {
            target.setApiKey(settings.apiKey() == null ? "" : settings.apiKey().strip());
        }
        if (!fromFile) {
            requireConfigured(target);
        }
    }

    private static void requireConfigured(FederationProperties.Llm target) {
        if (!target.isConfigured()) {
            throw new IllegalArgumentException("请填写接口地址和模型名");
        }
    }

    private void write() {
        settingsStore.write(new Settings(config.getProtocol(), config.getBaseUrl(), config.getApiKey(), config.getModel()));
    }

    private static FederationProperties.Llm copy(FederationProperties.Llm source) {
        FederationProperties.Llm copy = new FederationProperties.Llm();
        copy.setProtocol(source.getProtocol());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setModel(source.getModel());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        return copy;
    }

    static ChatModel build(FederationProperties.Llm config) {
        Duration timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        return switch (config.getProtocol().toLowerCase(Locale.ROOT)) {
            case "openai" -> OpenAiChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .modelName(config.getModel())
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .returnThinking(true)
                    .build();
            case "anthropic" -> AnthropicChatModel.builder()
                    .httpClientBuilder(AnthropicResponseGuard.httpClientBuilder())
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .modelName(config.getModel())
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .returnThinking(true)
                    .build();
            default -> throw new IllegalStateException("不支持的大模型协议: " + config.getProtocol() + "，可选 openai、anthropic");
        };
    }

    public record Settings(String protocol, String baseUrl, String apiKey, String model) {
    }
}
