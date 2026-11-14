package com.cmcc.cmdevops.security.federation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackService;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.feedback.MemoryAskFeedbackStore;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = AskFeedbackController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        RoleAuthorizationInterceptor.class,
        WebMvcSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        AskFeedbackService.class,
        AskFeedbackControllerWebTest.Stores.class
})
class AskFeedbackControllerWebTest {

    @TestConfiguration
    static class Stores {

        @Bean
        AskFeedbackStore askFeedbackStore() {
            return new MemoryAskFeedbackStore();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AskFeedbackStore store;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
        store.saveRun(new AskRun("run-1", "conv-1", "alice", "最近一天高危告警最多的资产",
                List.of(new Turn("user", "高危告警最多的资产"), new Turn("assistant", "要看哪个时间范围？")),
                AskRun.ANSWERED, "web-01 最多，5 条", "SELECT 1",
                Map.of("tables", List.of("ck81.pangu.alert"), "rows", List.of(List.of("web-01", 5))),
                null, 1_700_000_000_000L, 1_200));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void runCarriesHistoryEvidenceAndOwnRating() throws Exception {
        mockMvc.perform(get("/api/v1/ask/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.run.question").value("最近一天高危告警最多的资产"))
                .andExpect(jsonPath("$.run.history[1].role").value("assistant"))
                .andExpect(jsonPath("$.run.history[1].content").value("要看哪个时间范围？"))
                .andExpect(jsonPath("$.run.sql").value("SELECT 1"))
                .andExpect(jsonPath("$.run.evidence.tables[0]").value("ck81.pangu.alert"))
                .andExpect(jsonPath("$.run.evidence.rows[0][1]").value(5))
                .andExpect(jsonPath("$.run.createdAt").value(1_700_000_000_000L))
                .andExpect(jsonPath("$.rating").doesNotExist());

        mockMvc.perform(put("/api/v1/ask/runs/run-1/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"up\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("up"));
        mockMvc.perform(get("/api/v1/ask/runs/run-1"))
                .andExpect(jsonPath("$.rating").value("up"));

        mockMvc.perform(put("/api/v1/ask/runs/run-1/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").doesNotExist());
    }

    @Test
    void badRatingIs400AndUnknownRunIs404() throws Exception {
        mockMvc.perform(put("/api/v1/ask/runs/run-1/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"great\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("评价只能是 up 或 down"));

        mockMvc.perform(get("/api/v1/ask/runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("问数记录不存在：missing"));
    }

    @Test
    void suggestionLifecycle() throws Exception {
        mockMvc.perform(post("/api/v1/ask/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"高危应该按 level >= 4 算\",\"runId\":\"run-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.runQuestion").value("最近一天高危告警最多的资产"));

        mockMvc.perform(put("/api/v1/ask/suggestions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"adopted\"}"))
                .andExpect(status().isForbidden());

        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
        mockMvc.perform(put("/api/v1/ask/suggestions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"adopted\",\"disposition\":\"ontology\",\"reply\":\"已改派生概念\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ontology"))
                .andExpect(jsonPath("$.handledBy").value("root"));

        mockMvc.perform(get("/api/v1/ask/suggestions").param("status", "adopted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reply").value("已改派生概念"));
        mockMvc.perform(get("/api/v1/ask/suggestions").param("mine", "true"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/ask/suggestions").param("status", "done"))
                .andExpect(status().isBadRequest());

        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY)));
        mockMvc.perform(delete("/api/v1/ask/suggestions/1"))
                .andExpect(status().isConflict());

        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
        mockMvc.perform(delete("/api/v1/ask/suggestions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/v1/ask/suggestions"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void emptySuggestionIs400() throws Exception {
        mockMvc.perform(post("/api/v1/ask/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("建议内容不能为空"));
    }

    @Test
    void needsQueryRole() throws Exception {
        GatewayUser.set(new GatewayUser("ops", Set.of(GatewayRole.ADMIN)));

        mockMvc.perform(get("/api/v1/ask/suggestions"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ask/runs/run-1"))
                .andExpect(status().isForbidden());
    }
}
