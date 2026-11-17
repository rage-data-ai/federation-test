package com.cmcc.cmdevops.security.federation.ask.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.sun.net.httpserver.HttpServer;

class AskEmbedSettingsTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadsEnvAndReportsUnconfiguredWithoutEndpoint() {
        FederationProperties props = new FederationProperties();
        AskEmbedSettings settings = new AskEmbedSettings(props, new MemoryEmbedSettingsStore());
        settings.init();
        assertFalse(settings.configured());
        assertEquals("none", settings.embedderName());
        assertFalse((Boolean) settings.view().get("configured"));
    }

    @Test
    void updatePersistsAndExposesView() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String base = startEmbedServer(hits, 8);
        FederationProperties props = new FederationProperties();
        MemoryEmbedSettingsStore store = new MemoryEmbedSettingsStore();
        AskEmbedSettings settings = new AskEmbedSettings(props, store);
        settings.init();

        Map<String, Object> view = settings.update(new AskEmbedSettings.Settings(base, "k", "embedding-3"));
        assertTrue((Boolean) view.get("configured"));
        assertEquals(8, view.get("dim"));
        assertEquals("http", view.get("embedder"));
        assertTrue(hits.get() >= 1);
        assertEquals(base, store.read().endpoint());
        assertEquals("embedding-3", props.getAsk().getEmbedModel());
    }

    @Test
    void testDoesNotPersist() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String base = startEmbedServer(hits, 4);
        AskEmbedSettings settings = new AskEmbedSettings(new FederationProperties(), new MemoryEmbedSettingsStore());
        settings.init();
        Map<String, Object> result = settings.test(new AskEmbedSettings.Settings(base, "k", "m"));
        assertEquals(true, result.get("success"));
        assertEquals(4, result.get("dim"));
        assertFalse(settings.configured());
    }

    @Test
    void updateRequiresEndpoint() {
        AskEmbedSettings settings = new AskEmbedSettings(new FederationProperties(), new MemoryEmbedSettingsStore());
        settings.init();
        assertThrows(
                IllegalArgumentException.class,
                () -> settings.update(new AskEmbedSettings.Settings("", "k", "m")));
    }

    private String startEmbedServer(AtomicInteger hits, int dim) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            hits.incrementAndGet();
            StringBuilder emb = new StringBuilder("[");
            for (int i = 0; i < dim; i++) {
                if (i > 0) {
                    emb.append(',');
                }
                emb.append(i == 0 ? "1.0" : "0.0");
            }
            emb.append(']');
            byte[] body = ("{\"data\":[{\"embedding\":" + emb + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
