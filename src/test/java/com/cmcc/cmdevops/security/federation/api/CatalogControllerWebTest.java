package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.CatalogView;
import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.metadata.MetadataService;
import com.cmcc.cmdevops.security.federation.security.CatalogAclService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;
import com.cmcc.cmdevops.security.federation.service.QueryService;
import com.cmcc.cmdevops.security.federation.sql.DemoSqlLoader;

@WebMvcTest(controllers = CatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ RoleAuthorizationInterceptor.class, WebMvcSecurityConfiguration.class, GlobalExceptionHandler.class })
class CatalogControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private CatalogRegistry catalogRegistry;

    @MockitoBean
    private DemoSqlLoader demoSqlLoader;

    @MockitoBean
    private MetadataService metadataService;

    @MockitoBean
    private CatalogAclService catalogAcl;

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("tester", Set.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        when(catalogAcl.filter(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void listCatalogsReturnsViews() throws Exception {
        when(queryService.listCatalogs()).thenReturn(List.of(
                new CatalogView("mysql_shop", "mysql", "DIRECT", "db.example", 3306,
                        "shop", "u", null, null, false, true)));

        mockMvc.perform(get("/api/v1/catalogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mysql_shop"))
                .andExpect(jsonPath("$[0].type").value("mysql"))
                .andExpect(jsonPath("$[0].route").value("DIRECT"))
                .andExpect(jsonPath("$[0].hasPassword").value(true));
    }

    @Test
    void createCatalogReturnsSavedView() throws Exception {
        CatalogRecord saved = new CatalogRecord();
        saved.setName("mysql_shop");
        saved.setType("mysql");
        saved.setRoute("DIRECT");
        saved.setHost("db.example");
        saved.setPort(3306);
        saved.setDatabase("shop");
        saved.setUsername("u");
        saved.setPassword("p");
        when(catalogRegistry.save(any(CatalogRecord.class), eq(false))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "mysql_shop",
                                  "type": "mysql",
                                  "host": "db.example",
                                  "port": 3306,
                                  "database": "shop",
                                  "username": "u",
                                  "password": "p"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("mysql_shop"))
                .andExpect(jsonPath("$.hasPassword").value(true));
    }

    @Test
    void deleteCatalogNotFoundReturnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("数据源不存在: missing"))
                .when(catalogRegistry).delete("missing");

        mockMvc.perform(delete("/api/v1/catalogs/missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("数据源不存在: missing"));
    }

    @Test
    void createForbiddenWithoutAdminRole() throws Exception {
        GatewayUser.set(new GatewayUser("query-only", Set.of(GatewayRole.QUERY)));

        mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "mysql_shop",
                                  "type": "mysql",
                                  "host": "db.example"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
