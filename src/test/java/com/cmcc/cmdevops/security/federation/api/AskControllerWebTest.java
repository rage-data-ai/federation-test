package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.cmcc.cmdevops.security.federation.ask.AskListener;
import com.cmcc.cmdevops.security.federation.ask.AskService;
import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.llm.LlmClient.Settings;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = AskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ RoleAuthorizationInterceptor.class, WebMvcSecurityConfiguration.class, GlobalExceptionHandler.class })
class AskControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AskService askService;

    @MockitoBean
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("admin", Set.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", true);
        status.put("protocol", "openai");
        status.put("baseUrl", "http://llm.local");
        status.put("model", "qwen");
        when(llmClient.status()).thenReturn(status);
        Map<String, Object> view = new LinkedHashMap<>(status);
        view.put("apiKeySet", true);
        when(llmClient.view()).thenReturn(view);
        when(llmClient.update(any(Settings.class))).thenReturn(view);
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void llmStatusAndConfig() throws Exception {
        mockMvc.perform(get("/api/v1/llm/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("qwen"));

        mockMvc.perform(get("/api/v1/llm/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(true));
    }

    @Test
    void updateAndTestLlm() throws Exception {
        when(llmClient.test(any(Settings.class))).thenReturn("ok");

        mockMvc.perform(put("/api/v1/llm/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "protocol": "openai",
                                  "baseUrl": "http://llm.local/v1",
                                  "apiKey": "secret",
                                  "model": "qwen"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(true));
        verify(llmClient).update(any(Settings.class));

        mockMvc.perform(post("/api/v1/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "protocol": "openai",
                                  "baseUrl": "http://llm.local/v1",
                                  "apiKey": "secret",
                                  "model": "qwen"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    @Test
    void testLlmFailureReturnsMessage() throws Exception {
        when(llmClient.test(any(Settings.class))).thenThrow(new IllegalStateException("连接失败"));

        mockMvc.perform(post("/api/v1/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "protocol": "openai",
                                  "baseUrl": "http://llm.local/v1",
                                  "apiKey": "secret",
                                  "model": "qwen"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("连接失败"));
    }

    @Test
    void askStreamsDone() throws Exception {
        doAnswer(invocation -> {
            AskListener listener = invocation.getArgument(1);
            listener.emit("answer", Map.of("text", "42"));
            return null;
        }).when(askService).ask(any(AskRequest.class), any());

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"今天有多少告警？\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("done")));
    }

    @Test
    void askErrorIsStreamed() throws Exception {
        doThrow(new IllegalStateException("llm-not-configured"))
                .when(askService).ask(any(AskRequest.class), any());

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("event:error")))
                .andExpect(content().string(Matchers.containsString("llm-not-configured")));
    }

    @Test
    void updateForbiddenWithoutAdmin() throws Exception {
        GatewayUser.set(new GatewayUser("query-only", Set.of(GatewayRole.QUERY)));
        mockMvc.perform(put("/api/v1/llm/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "protocol": "openai",
                                  "baseUrl": "http://llm.local/v1",
                                  "apiKey": "secret",
                                  "model": "qwen"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
