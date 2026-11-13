package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

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

import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;
import com.cmcc.cmdevops.security.federation.service.QueryService;

@WebMvcTest(controllers = QueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ RoleAuthorizationInterceptor.class, WebMvcSecurityConfiguration.class, GlobalExceptionHandler.class })
class QueryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("tester", Set.of(GatewayRole.QUERY)));
        when(queryService.execute(any())).thenReturn(new QueryResponse(
                true, EngineType.TRINO.name(), "ok", List.of("mysql"), "SELECT 1",
                List.of(new QueryResponse.ColumnMeta("n", "bigint")), List.of(List.of(1)), 1, 3, false, null));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void explainReturnsRoutePlan() throws Exception {
        mockMvc.perform(post("/api/v1/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.engine").value("TRINO"));
    }

    @Test
    void queryForbiddenWithoutQueryRole() throws Exception {
        GatewayUser.set(new GatewayUser("admin-only", Set.of(GatewayRole.ADMIN)));
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isForbidden());
    }
}
