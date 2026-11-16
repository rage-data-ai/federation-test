package com.cmcc.cmdevops.security.federation.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.cmcc.cmdevops.security.federation.account.MemoryUserStore;
import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.account.UserService.Session;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@EnableConfigurationProperties(FederationProperties.class)
@Import({
        RoleAuthorizationInterceptor.class,
        WebMvcSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        UserControllerWebTest.Accounts.class
})
class UserControllerWebTest {

    @TestConfiguration
    static class Accounts {
        @Bean
        MemoryUserStore memoryUserStore() {
            return new MemoryUserStore();
        }

        @Bean
        UserService userService(MemoryUserStore store, FederationProperties properties) {
            return new UserService(store, properties, new BCryptPasswordEncoder(4), System::currentTimeMillis);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FederationProperties properties;

    @Autowired
    private UserService users;

    @Autowired
    private MemoryUserStore userStore;

    @BeforeEach
    void setUp() {
        userStore.clear();
        properties.getSecurity().setEnabled(true);
        users.bootstrapAdmin("root", "root-pass-11", "管理员");
        GatewayUser.set(new GatewayUser("root", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void adminListsUpdatesAndResetsPassword() throws Exception {
        Session grace = users.register("grace", "grace-pass-1", null);
        long id = grace.user().id();

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'grace')].status").value("active"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        mockMvc.perform(put("/api/v1/users/" + id)
                        .contentType("application/json")
                        .content("{\"roles\":[\"ADMIN\"],\"displayName\":\"格蕾丝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.roles[1]").value("QUERY"))
                .andExpect(jsonPath("$.displayName").value("格蕾丝"));

        mockMvc.perform(put("/api/v1/users/" + id)
                        .contentType("application/json")
                        .content("{\"roles\":[\"OWNER\"]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/users/" + id + "/password")
                        .contentType("application/json")
                        .content("{\"password\":\"grace-pass-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertNull(users.authenticate(grace.token()));
        assertNotNull(users.login("grace", "grace-pass-2"));

        mockMvc.perform(put("/api/v1/users/" + id)
                        .contentType("application/json")
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));

        mockMvc.perform(put("/api/v1/users/424242")
                        .contentType("application/json")
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        long id = users.register("heidi", "heidi-pass-1", null).user().id();
        GatewayUser.set(new GatewayUser("heidi", Set.of(GatewayRole.QUERY)));

        mockMvc.perform(get("/api/v1/users")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/users/" + id)
                        .contentType("application/json")
                        .content("{\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/" + id + "/password")
                        .contentType("application/json")
                        .content("{\"password\":\"heidi-pass-2\"}"))
                .andExpect(status().isForbidden());
    }
}
