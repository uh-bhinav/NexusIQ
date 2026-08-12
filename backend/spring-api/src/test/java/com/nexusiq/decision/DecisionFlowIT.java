package com.nexusiq.decision;

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
 * Unlike Auth/Workspace/Approval/Audit, DecisionController had no
 * end-to-end HTTP test through the real security filter chain + Postgres —
 * cross-tenant denial was only verified at the mocked-repository unit level
 * (DecisionServiceTest). This proves it the same way WorkspaceFlowIT proves
 * its own cross-tenant cases. Doesn't wait for the async decision.requested
 * -> ai-service workflow to complete (that's Kafka-consumer/graph territory,
 * covered elsewhere) — the request row exists synchronously on create,
 * which is all a 404-vs-200 check needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(
        statements =
                "TRUNCATE TABLE audit_events, decision_requests, workspace_members, workspaces, documents, users CASCADE")
class DecisionFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createDecision_thenListAndGet_showsIt() throws Exception {
        String token = registerAndLogin("requester@acme.com", "Requester", "password12345");
        String workspaceId = createWorkspace(token, "Decision Workspace");

        String decisionId = createDecision(token, workspaceId, "Should Vendor Alpha be approved?");

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/decisions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(decisionId));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("Should Vendor Alpha be approved?"));
    }

    @Test
    void userOutsideWorkspace_cannotReadItsDecision_returns404() throws Exception {
        String ownerToken = registerAndLogin("decowner@acme.com", "DecOwner", "password12345");
        String outsiderToken = registerAndLogin("decoutsider@acme.com", "DecOutsider", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Private Decision Workspace");
        String decisionId = createDecision(ownerToken, workspaceId, "Should Vendor Beta be approved?");

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/decisions")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void userOutsideWorkspace_cannotCreateADecisionInIt_returns404() throws Exception {
        String ownerToken = registerAndLogin("decowner2@acme.com", "DecOwner2", "password12345");
        String outsiderToken = registerAndLogin("decoutsider2@acme.com", "DecOutsider2", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Another Private Workspace");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/decisions")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"title": "Sneaky", "question": "Should this be approved?", "priority": "NORMAL"}
                                """))
                .andExpect(status().isNotFound());
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

    private String createDecision(String token, String workspaceId, String question) throws Exception {
        String body =
                """
                {"title": "Test decision", "question": "%s", "priority": "NORMAL"}
                """
                        .formatted(question);
        String response = mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }
}
