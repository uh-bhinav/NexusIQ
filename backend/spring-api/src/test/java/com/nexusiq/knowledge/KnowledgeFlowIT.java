package com.nexusiq.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * KnowledgeController had no end-to-end HTTP test through the real security
 * filter chain — only KnowledgeServiceTest's mocked-RestClient unit test.
 * {@code KnowledgeService.search} checks workspace membership (via
 * {@code WorkspaceAccessService.requireMembership}) before ever calling out
 * to ai-service, so a cross-tenant denial test needs no ai-service stub at
 * all — the request 404s before the outbound HTTP call would happen. The
 * successful-search path (what a real 200 response looks like) is already
 * covered by KnowledgeServiceTest's MockRestServiceServer-based tests; not
 * duplicated here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events, workspace_members, workspaces, documents, users CASCADE")
class KnowledgeFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userOutsideWorkspace_cannotSearchIt_returns404_withoutCallingAiService() throws Exception {
        String ownerToken = registerAndLogin("knowowner@acme.com", "KnowOwner", "password12345");
        String outsiderToken = registerAndLogin("knowoutsider@acme.com", "KnowOutsider", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Private Knowledge Workspace");

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/knowledge/search")
                        .param("q", "data residency")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- helpers (mirrors WorkspaceFlowIT's established pattern) ----

    private String registerAndLogin(String email, String name, String password) throws Exception {
        String body =
                """
                {"email": "%s", "name": "%s", "password": "%s"}
                """
                        .formatted(email, name, password);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("access_token").asString();
    }

    private String createWorkspace(String token, String name) throws Exception {
        String body = """
                {"name": "%s"}
                """.formatted(name);
        String response = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asString();
    }
}
