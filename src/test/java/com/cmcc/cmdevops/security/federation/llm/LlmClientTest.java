package com.cmcc.cmdevops.security.federation.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import dev.langchain4j.data.message.AiMessage;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;

class LlmClientTest {

    @Test
    void savesConfigWithoutExposingKeyAndBlankKeyKeepsOld() {
        MemoryLlmSettingsStore store = new MemoryLlmSettingsStore();
        FederationProperties.Llm config = new FederationProperties.Llm();
        LlmClient client = new LlmClient(config, store);

        client.update(new Settings("OpenAI", " http://llm.local/v1 ", "secret-key", " qwen "));

        assertEquals("openai", config.getProtocol());
        assertEquals("http://llm.local/v1", config.getBaseUrl());
        assertEquals("qwen", config.getModel());
        assertEquals("secret-key", config.getApiKey());
        assertEquals(true, client.view().get("apiKeySet"));
        assertFalse(client.view().toString().contains("secret-key"));

        client.update(new Settings("anthropic", "http://llm.local/v1", "  ", "claude"));

        assertEquals("anthropic", config.getProtocol());
        assertEquals("secret-key", config.getApiKey());

        FederationProperties.Llm reloaded = new FederationProperties.Llm();
        new LlmClient(reloaded, store).init();
        assertEquals("claude", reloaded.getModel());
        assertEquals("secret-key", reloaded.getApiKey());
        assertTrue(reloaded.isConfigured());
    }

    @Test
    void emptyAnswerUsesJsonInsideThinking() {
        AiMessage message = AiMessage.builder().thinking("先看列 {\"attributes\":[]}").build();
        assertEquals("先看列 {\"attributes\":[]}", LlmClient.replyText(message));
        assertEquals("{\"attributes\":[]}", LlmClient.replyText(AiMessage.from("{\"attributes\":[]}")));
    }

    @Test
    void truncatedJsonIsReportedAsCutOff() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> JsonReply.extractObject("{ \"concepts\": [ { \"name\": \"WindowsSecurityEvent\""));
        assertTrue(error.getMessage().startsWith("大模型返回的 JSON 没写完"));
    }

    @Test
    void anthropicResponseWithoutContentIsExplained() {
        IllegalArgumentException openai = assertThrows(IllegalArgumentException.class,
                () -> AnthropicResponseGuard.normalize("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        assertTrue(openai.getMessage().contains("openai"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AnthropicResponseGuard.normalize("{\"type\":\"message\",\"content\":null}"));
        assertTrue(missing.getMessage().contains("content"));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> AnthropicResponseGuard.normalize("{\"type\":\"error\",\"error\":{\"message\":\"invalid model\"}}"));
        assertTrue(rejected.getMessage().contains("invalid model"));

        String rewritten = AnthropicResponseGuard.normalize("{\"content\":\"ok\"}");
        assertTrue(rewritten.contains("\"type\":\"text\""));
        assertTrue(rewritten.contains("ok"));
    }

    @Test
    void nullContentExceptionIsExplained() {
        String message = AnthropicResponseGuard.explain(
                new NullPointerException("Cannot invoke \"java.util.List.stream()\" because \"contents\" is null"));
        assertTrue(message.contains("openai"));
    }

    @Test
    void rejectsUnknownProtocol() {
        LlmClient client = new LlmClient(new FederationProperties.Llm(), new MemoryLlmSettingsStore());
        assertThrows(IllegalArgumentException.class,
                () -> client.update(new Settings("ollama", "http://llm", "", "m")));
    }
}
