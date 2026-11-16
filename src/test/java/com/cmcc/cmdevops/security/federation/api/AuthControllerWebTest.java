package com.cmcc.cmdevops.security.federation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.cmcc.cmdevops.security.federation.account.MemoryUserStore;
import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.security.BearerAuthFilter;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@EnableConfigurationProperties(FederationProperties.class)
@Import({
        RoleAuthorizationInterceptor.class,
        WebMvcSecurityConfiguration.class,
        GlobalExceptionHandler.class,
        com.cmcc.cmdevops.security.federation.security.CatalogAclService.class,
        com.cmcc.cmdevops.security.federation.security.TokenRevocationService.class,
        AuthControllerWebTest.Accounts.class
})
class AuthControllerWebTest {

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
    private UserService users;

    @Autowired
    private MemoryUserStore userStore;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FederationProperties properties;

    @BeforeEach
    void setUp() {
        userStore.clear();
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getJwt().setIssuerUri("https://idp.example/realms/demo");
        users.bootstrapAdmin("root", "root-pass-11", "管理员");
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
        properties.getSecurity().getJwt().setIssuerUri("");
        properties.getSecurity().setBackchannelLogoutSecret("");
    }

    @Test
    void meReturnsCurrentIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.displayName").value("alice"))
                .andExpect(jsonPath("$.localAccount").value(false))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.roles[1]").value("QUERY"))
                .andExpect(jsonPath("$.securityEnabled").value(true))
                .andExpect(jsonPath("$.jwtEnabled").value(true))
                .andExpect(jsonPath("$.catalogAcl").value(false))
                .andExpect(jsonPath("$.catalogs[0]").value("*"));
    }

    @Test
    void logoutReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer unused"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void backchannelLogoutWithSecret() throws Exception {
        properties.getSecurity().setBackchannelLogoutSecret("bc-secret");
        GatewayUser.set(new GatewayUser("anon", Set.of(GatewayRole.QUERY)));
        mockMvc.perform(post("/api/v1/auth/backchannel-logout")
                        .contentType("application/json")
                        .header("X-Backchannel-Secret", "bc-secret")
                        .content("{\"token\":\"victim-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        properties.getSecurity().setBackchannelLogoutSecret("");
    }

    @Test
    void backchannelLogoutRejectedWithoutAuth() throws Exception {
        properties.getSecurity().setBackchannelLogoutSecret("bc-secret");
        GatewayUser.set(new GatewayUser("reader", Set.of(GatewayRole.QUERY)));
        mockMvc.perform(post("/api/v1/auth/backchannel-logout")
                        .contentType("application/json")
                        .content("{\"token\":\"x\"}"))
                .andExpect(status().isForbidden());
        properties.getSecurity().setBackchannelLogoutSecret("");
    }

    @Test
    void registerThenLoginReturnSessionTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"carol\",\"password\":\"carol-pass-1\",\"displayName\":\"卡萝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andExpect(jsonPath("$.user.username").value("carol"))
                .andExpect(jsonPath("$.user.displayName").value("卡萝"))
                .andExpect(jsonPath("$.user.roles[0]").value("QUERY"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"carol\",\"password\":\"carol-pass-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("用户名已被占用"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"carol\",\"password\":\"carol-pass-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"carol\",\"password\":\"wrong-pass-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"dave\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setupStatusAndBootstrapAdmin() throws Exception {
        userStore.clear();
        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSetup").value(true));

        mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin-pass-1\",\"displayName\":\"管理员\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.user.roles[1]").value("QUERY"));

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSetup").value(false));

        mockMvc.perform(post("/api/v1/auth/setup")
                        .contentType("application/json")
                        .content("{\"username\":\"other\",\"password\":\"other-pass-1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void localAccountSeesDisplayNameChangesPasswordAndLogsOut() throws Exception {
        String token = users.register("frank", "frank-pass-1", "弗兰克").token();
        GatewayUser.set(users.authenticate(token));

        mockMvc.perform(get("/api/v1/auth/me")
                        .requestAttr(BearerAuthFilter.LOCAL_ACCOUNT_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("frank"))
                .andExpect(jsonPath("$.displayName").value("弗兰克"))
                .andExpect(jsonPath("$.localAccount").value(true));

        mockMvc.perform(post("/api/v1/auth/password")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"frank-pass-1\",\"newPassword\":\"frank-pass-2\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/password")
                        .requestAttr(BearerAuthFilter.LOCAL_ACCOUNT_ATTRIBUTE, Boolean.TRUE)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"wrong-pass-1\",\"newPassword\":\"frank-pass-2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("原密码不正确"));

        mockMvc.perform(post("/api/v1/auth/password")
                        .requestAttr(BearerAuthFilter.LOCAL_ACCOUNT_ATTRIBUTE, Boolean.TRUE)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"frank-pass-1\",\"newPassword\":\"frank-pass-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        org.junit.jupiter.api.Assertions.assertNotNull(users.authenticate(token));

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        org.junit.jupiter.api.Assertions.assertNull(users.authenticate(token));
    }
}
