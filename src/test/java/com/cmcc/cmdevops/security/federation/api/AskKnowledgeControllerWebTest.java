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

import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.knowledge.KnowledgeVectorIndex;
import com.cmcc.cmdevops.security.federation.ask.knowledge.MemoryAskKnowledgeStore;
import com.cmcc.cmdevops.security.federation.ask.ontologytools.HashingTermEmbedder;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = AskKnowledgeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        RoleAuthorizationInterceptor.class,
        WebMvcSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        AskKnowledgeControllerWebTest.Stores.class
})
class AskKnowledgeControllerWebTest {

    @TestConfiguration
    static class Stores {

        @Bean
        AskKnowledgeService askKnowledgeService() {
            return new AskKnowledgeService(
                    new MemoryAskKnowledgeStore(),
                    new KnowledgeVectorIndex(new HashingTermEmbedder(), null, JsonMapper.builder().build()),
                    System::currentTimeMillis);
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
    void createApproveSearchAndDelete() throws Exception {
        mockMvc.perform(post("/api/v1/ask/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"最近一天高危告警有多少？","answer":"共 12 条","sql":"SELECT 1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.id").value(1));

        mockMvc.perform(get("/api/v1/ask/knowledge").param("status", "approved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/v1/ask/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"最近一天高危告警有多少？\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].knowledge.id").value(1));

        mockMvc.perform(put("/api/v1/ask/knowledge/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"rejected\",\"reviewNote\":\"过时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));

        mockMvc.perform(delete("/api/v1/ask/knowledge/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
