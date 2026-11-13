package com.cmcc.cmdevops.security.federation.llm;

import java.time.Duration;
import java.util.ServiceLoader;

import javax.net.ssl.SSLHandshakeException;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic 客户端在响应没有 content 数组时会直接对 null 调用 stream()。
 * 这里在解析前把常见的不兼容响应改成明确错误，或把字符串 content 改成数组。
 */
final class AnthropicResponseGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicResponseGuard() {
    }

    static HttpClientBuilder httpClientBuilder() {
        return new GuardedClientBuilder();
    }

    static String normalize(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("大模型返回了空响应");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return body;
        }
        if (!(root instanceof ObjectNode object)) {
            return body;
        }
        String error = errorMessage(object);
        if (error != null) {
            throw new IllegalArgumentException("大模型接口拒绝了请求：" + error);
        }
        if (object.has("choices")) {
            throw new IllegalArgumentException("接口返回的是 OpenAI 格式。协议请改成 openai，不要选 anthropic");
        }
        JsonNode content = object.get("content");
        if (content == null || content.isNull()) {
            throw new IllegalArgumentException("接口没有返回 content 内容。如果这是 OpenAI 兼容地址，协议请选 openai");
        }
        if (!content.isTextual()) {
            return body;
        }
        ArrayNode blocks = JSON.createArrayNode();
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text");
        block.put("text", content.asText());
        blocks.add(block);
        object.set("content", blocks);
        return JSON.writeValueAsString(object);
    }

    static String explain(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("because \"contents\" is null")) {
                return "接口没有返回 content 内容。如果这是 OpenAI 兼容地址，协议请选 openai";
            }
            if (cursor instanceof SSLHandshakeException || (message != null && message.contains("SSLHandshakeException"))) {
                return "HTTPS 握手被对方中断。请确认接口地址支持 HTTPS，并且协议和地址匹配";
            }
            if (message != null && !message.isBlank() && cursor instanceof IllegalArgumentException) {
                return message;
            }
            cursor = cursor.getCause();
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }

    private static String errorMessage(ObjectNode object) {
        JsonNode error = object.get("error");
        if (error == null || !error.isObject()) {
            return null;
        }
        JsonNode message = error.get("message");
        if (message == null || message.isNull() || message.asText().isBlank()) {
            return null;
        }
        return message.asText();
    }

    private static final class GuardedClientBuilder implements HttpClientBuilder {

        private final HttpClientBuilder delegate = ServiceLoader.load(HttpClientBuilderFactory.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可用的 HTTP 客户端"))
                .create();

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            delegate.connectTimeout(timeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            delegate.readTimeout(timeout);
            return this;
        }

        @Override
        public HttpClient build() {
            return new GuardedClient(delegate.build());
        }
    }

    private static final class GuardedClient implements HttpClient {

        private final HttpClient delegate;

        private GuardedClient(HttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            SuccessfulHttpResponse response = delegate.execute(request);
            String body = response.body();
            String normalized = normalize(body);
            if (normalized.equals(body)) {
                return response;
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers())
                    .body(normalized)
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            delegate.execute(request, parser, listener);
        }
    }
}
