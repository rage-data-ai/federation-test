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
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
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
        com.cmcc.cmdevops.security.federation.security.TokenRevocationService.class
})
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FederationProperties properties;

    @BeforeEach
    void setUp() {
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().getJwt().setIssuerUri("https://idp.example/realms/demo");
        GatewayUser.set(new GatewayUser("alice", Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
        properties.getSecurity().setEnabled(false);
        properties.getSecurity().getJwt().setIssuerUri("");
    }

    @Test
    void meReturnsCurrentIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("alice"))
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

}
