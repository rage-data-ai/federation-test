package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpTermEmbedderTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesOpenAiCompatibleEmbedding() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":[0.0,3.0,4.0]}]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        HttpTermEmbedder embedder = new HttpTermEmbedder(
                "https://example.test/v1",
                "key",
                "demo-model",
                new HashingTermEmbedder(),
                new ObjectMapper(),
                http);
        float[] vector = embedder.embed("告警");
        assertEquals(3, vector.length);
        assertEquals(0.0f, vector[0], 1e-5);
        assertEquals(0.6f, vector[1], 1e-5);
        assertEquals(0.8f, vector[2], 1e-5);
        assertEquals("http", embedder.name());
    }

    @Test
    void fallsBackWhenHttpFails() {
        HttpTermEmbedder embedder = new HttpTermEmbedder(
                "http://127.0.0.1:1/v1/embeddings",
                "",
                "x",
                new HashingTermEmbedder(64),
                new ObjectMapper(),
                HttpClient.newHttpClient());
        float[] vector = embedder.embed("高危告警");
        assertEquals(64, vector.length);
        assertTrue(HashingTermEmbedder.cosine(vector, vector) > 0.99);
    }
}
