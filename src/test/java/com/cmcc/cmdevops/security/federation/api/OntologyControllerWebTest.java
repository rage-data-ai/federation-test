package com.cmcc.cmdevops.security.federation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

import com.cmcc.cmdevops.security.federation.config.WebMvcSecurityConfiguration;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftPhase;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftRequest;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftResult;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;
import com.cmcc.cmdevops.security.federation.ontology.OntologyVersionService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@WebMvcTest(controllers = OntologyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ RoleAuthorizationInterceptor.class, WebMvcSecurityConfiguration.class, GlobalExceptionHandler.class })
class OntologyControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OntologyRepository repository;

    @MockitoBean
    private OntologyDraftService draftService;

    @MockitoBean
    private OntologyStore store;

    @MockitoBean
    private OntologyVersionService versions;

    private final Snapshot empty = new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of());

    @BeforeEach
    void setUp() {
        GatewayUser.set(new GatewayUser("admin", Set.of(GatewayRole.ADMIN, GatewayRole.QUERY)));
        when(repository.snapshot()).thenReturn(empty);
        when(repository.turtle()).thenReturn("@prefix : <http://example/> .");
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void snapshotAndTurtle() throws Exception {
        mockMvc.perform(get("/api/v1/ontology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts").isArray());

        mockMvc.perform(get("/api/v1/ontology/turtle"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "turtle")))
                .andExpect(content().string("@prefix : <http://example/> ."));
    }

    @Test
    void saveConceptAttributeRelationCodeDefinedAndDelete() throws Exception {
        mockMvc.perform(put("/api/v1/ontology/concepts/Alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alert",
                                  "labelZh": "告警",
                                  "labelEn": "Alert",
                                  "aliases": [],
                                  "comment": "c",
                                  "parent": null,
                                  "table": "ck.pangu.alert",
                                  "timeColumn": "ts",
                                  "largeTable": true,
                                  "subject": "安全"
                                }
                                """))
                .andExpect(status().isOk());
        verify(repository).saveConcept(any(Concept.class));

        mockMvc.perform(put("/api/v1/ontology/attributes/level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "level",
                                  "labelZh": "级别",
                                  "labelEn": "level",
                                  "aliases": [],
                                  "comment": null,
                                  "domain": "Alert",
                                  "datatype": "string",
                                  "column": "lvl",
                                  "unit": null,
                                  "identifier": false,
                                  "codeList": "AlertLevel"
                                }
                                """))
                .andExpect(status().isOk());
        verify(repository).saveAttribute(any(Attribute.class));

        mockMvc.perform(put("/api/v1/ontology/relations/hasAsset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "hasAsset",
                                  "labelZh": "关联资产",
                                  "labelEn": "hasAsset",
                                  "aliases": [],
                                  "comment": null,
                                  "domain": "Alert",
                                  "range": "Asset",
                                  "joinFrom": "asset_id",
                                  "joinTo": "id",
                                  "inverse": null,
                                  "transitive": false,
                                  "functional": true
                                }
                                """))
                .andExpect(status().isOk());
        verify(repository).saveRelation(any(Relation.class));

        mockMvc.perform(put("/api/v1/ontology/codes/high")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "high",
                                  "codeList": "AlertLevel",
                                  "value": "1",
                                  "labelZh": "高",
                                  "labelEn": "high",
                                  "aliases": [],
                                  "comment": null
                                }
                                """))
                .andExpect(status().isOk());
        verify(repository).saveCode(any(Code.class));

        mockMvc.perform(put("/api/v1/ontology/defined/HighAlert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "HighAlert",
                                  "labelZh": "高危告警",
                                  "labelEn": "HighAlert",
                                  "aliases": [],
                                  "comment": null,
                                  "base": "Alert",
                                  "conditions": [{"attribute":"level","operator":"=","value":"1"}],
                                  "definitionText": null
                                }
                                """))
                .andExpect(status().isOk());
        verify(repository).saveDefinedConcept(any(DefinedConcept.class));

        mockMvc.perform(put("/api/v1/ontology/subjects/旧主题")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新主题\"}"))
                .andExpect(status().isOk());
        verify(repository).renameSubject(eq("旧主题"), eq("新主题"));

        mockMvc.perform(delete("/api/v1/ontology/entities/Alert"))
                .andExpect(status().isOk());
        verify(repository).delete("Alert");
    }

    @Test
    void reasonMaterializesStore() throws Exception {
        when(store.inferredSize()).thenReturn(42L);

        mockMvc.perform(post("/api/v1/ontology/reason"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inferredTriples").value(42));
        verify(store).materialize();
    }

    @Test
    void draftStreamsResult() throws Exception {
        DraftResult result = new DraftResult(1, 2, 0, 0, 0, List.of());
        doAnswer(invocation -> {
            Consumer<DraftPhase> progress = invocation.getArgument(1);
            progress.accept(new DraftPhase("scan", "扫描表", "start", 0, List.of()));
            return result;
        }).when(draftService).draft(any(DraftRequest.class), any());

        MvcResult mvcResult = mockMvc.perform(post("/api/v1/ontology/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tables\":[\"ck.pangu.alert\"],\"instructions\":\"\",\"subject\":\"安全\"}"))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("result")));
    }

    @Test
    void mutateForbiddenWithoutAdmin() throws Exception {
        GatewayUser.set(new GatewayUser("query-only", Set.of(GatewayRole.QUERY)));
        mockMvc.perform(post("/api/v1/ontology/reason"))
                .andExpect(status().isForbidden());
    }
}
