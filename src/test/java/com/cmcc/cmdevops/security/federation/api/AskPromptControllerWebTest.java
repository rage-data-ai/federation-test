package com.cmcc.cmdevops.security.federation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService;
import com.cmcc.cmdevops.security.federation.ask.prompt.MemoryAskPromptStore;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = AskPromptController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        RoleAuthorizationInterceptor.class,
        WebMvcSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        AskPromptControllerWebTest.Stores.class
})
class AskPromptControllerWebTest {

    @TestConfiguration
    static class Stores {

        @Bean
        AskPromptService askPromptService() {
            return new AskPromptService(new MemoryAskPromptStore(), System::currentTimeMillis);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void createActivateAndDeleteGlobalPrompt() throws Exception {
        mockMvc.perform(post("/api/v1/ask/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"global\",\"content\":\"默认看最近 7 天\",\"source\":\"human\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.id").value(1));

        mockMvc.perform(post("/api/v1/ask/prompts/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        mockMvc.perform(get("/api/v1/ask/prompts").param("scope", "global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(put("/api/v1/ask/prompts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"默认看最近 14 天\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("默认看最近 14 天"));

        mockMvc.perform(delete("/api/v1/ask/prompts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
