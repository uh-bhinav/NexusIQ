package com.nexusiq.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /audit/resource/{resourceType}/{resourceId} previously had no workspace
 * membership check at all (a real, previously-shipped cross-tenant leak — any
 * authenticated user could pull any workspace's resource-scoped audit history
 * by guessing a resource type + UUID). Now requires workspaceId and enforces
 * membership exactly like GET /audit does. .claude/rules/security.md: "Cross-
 * tenant leakage is the single worst failure this system can have."
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events, workspace_members, workspaces, documents, users CASCADE")
class AuditFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userOutsideWorkspace_cannotReadItsResourceAuditHistory_returns404() throws Exception {
        String ownerToken = registerAndLogin("owner@acme.com", "Owner", "password12345");
        String outsiderToken = registerAndLogin("outsider@acme.com", "Outsider", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Private Workspace");
        String documentId = uploadDocument(ownerToken, workspaceId, "policy.txt", "confidential policy text");

        mockMvc.perform(get("/api/v1/audit/resource/DOCUMENT/" + documentId + "?workspaceId=" + workspaceId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void memberOfWorkspace_canReadItsResourceAuditHistory() throws Exception {
        // Confirms the 404 above is a real access-control decision, not a
        // broken endpoint — same pairing as WorkspaceFlowIT's own cross-tenant
        // tests.
        String ownerToken = registerAndLogin("owner2@acme.com", "Owner2", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Owned Workspace");
        String documentId = uploadDocument(ownerToken, workspaceId, "policy.txt", "confidential policy text");

        mockMvc.perform(get("/api/v1/audit/resource/DOCUMENT/" + documentId + "?workspaceId=" + workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.event_type == 'DOCUMENT_UPLOADED')]").exists());
    }

    @Test
    void requestWithoutWorkspaceId_isRejected() throws Exception {
        String ownerToken = registerAndLogin("owner3@acme.com", "Owner3", "password12345");
        String workspaceId = createWorkspace(ownerToken, "Another Workspace");
        String documentId = uploadDocument(ownerToken, workspaceId, "policy.txt", "confidential policy text");

        mockMvc.perform(get("/api/v1/audit/resource/DOCUMENT/" + documentId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is4xxClientError());
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

    private String uploadDocument(String token, String workspaceId, String name, String content) throws Exception {
        MockMultipartFile filePart =
                new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "metadata",
                MediaType.APPLICATION_JSON_VALUE,
                ("""
                {"name": "%s", "document_type": "SECURITY_POLICY"}
                """
                        .formatted(name))
                        .getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/workspaces/" + workspaceId + "/documents")
                        .file(filePart)
                        .file(metadataPart)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }
}
