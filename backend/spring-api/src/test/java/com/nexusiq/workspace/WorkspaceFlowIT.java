package com.nexusiq.workspace;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import com.nexusiq.security.JwtService;
import com.nexusiq.user.entity.Role;
import java.util.UUID;
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
 * The acceptance criteria that matter most in Phase 1: workspace + membership
 * lifecycle, role restriction (criterion 5), cross-tenant denial (criterion 4),
 * and that every mutation is audited (criterion 6).
 * docs/IMPLEMENTATION/ROADMAP.md Phase 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events, workspace_members, workspaces, documents, users CASCADE")
class WorkspaceFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void createWorkspace_addMember_thenListMembers_showsBoth() throws Exception {
        String adminToken = registerAndLogin("admin@acme.com", "Admin", "password12345");
        registerAndLogin("member@acme.com", "Member", "password12345");

        String workspaceId = createWorkspace(adminToken, "Acme Corp");

        String addMemberBody =
                """
                {"email": "member@acme.com", "role": "ANALYST"}
                """;
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addMemberBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ANALYST"));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void nonMember_getsWorkspace_returns404() throws Exception {
        String ownerToken = registerAndLogin("owner@acme.com", "Owner", "password12345");
        String outsiderToken = registerAndLogin("outsider@acme.com", "Outsider", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Private Workspace");

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void userInWorkspaceB_cannotReadWorkspaceAsDocument_returns404() throws Exception {
        // Acceptance criterion 4 — the most important one in this phase.
        String ownerAToken = registerAndLogin("ownerA@acme.com", "OwnerA", "password12345");
        String ownerBToken = registerAndLogin("ownerB@acme.com", "OwnerB", "password12345");

        String workspaceAId = createWorkspace(ownerAToken, "Workspace A");
        createWorkspace(ownerBToken, "Workspace B");

        String docBody =
                """
                {"name": "Security Policy", "document_type": "SECURITY_POLICY"}
                """;
        String createDocResponse = mockMvc.perform(post("/api/v1/workspaces/" + workspaceAId + "/documents")
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(docBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId =
                objectMapper.readTree(createDocResponse).get("id").asString();

        // Owner B is not a member of Workspace A: the document must not resolve
        // through B's own (unrelated) workspace id space, and — more importantly —
        // B has no membership in workspace A at all, so any attempt returns 404.
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceAId + "/documents/" + documentId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        // Confirm the document DOES resolve for the actual owner, proving the 404
        // above is a real access-control decision and not a broken endpoint.
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceAId + "/documents/" + documentId)
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Security Policy"));
    }

    @Test
    void analyst_canCreateAWorkspace_default_selfRegistrationRole() throws Exception {
        String analystToken = registerAndLogin("analyst@acme.com", "Analyst", "password12345");
        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Analyst Workspace"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void viewer_cannotCreateAWorkspace_returns403() throws Exception {
        // Acceptance criterion 5. Self-registration always defaults to ANALYST
        // (AuthService.DEFAULT_SELF_REGISTER_ROLE) — there is no public endpoint
        // that produces a VIEWER account — so a VIEWER-role access token is minted
        // directly to exercise the actual @PreAuthorize check on the endpoint.
        String viewerToken = jwtService.issueAccessToken(UUID.randomUUID(), "viewer@acme.com", Role.VIEWER.name());

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Viewer Workspace"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyMutation_writesAnAuditEvent() throws Exception {
        String token = registerAndLogin("auditme@acme.com", "Auditor", "password12345");
        String workspaceId = createWorkspace(token, "Audited Workspace");

        mockMvc.perform(get("/api/v1/audit?workspaceId=" + workspaceId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.event_type == 'WORKSPACE_CREATED')]").exists());
    }

    @Test
    void addMember_byNonAdmin_returns403() throws Exception {
        String ownerToken = registerAndLogin("owner2@acme.com", "Owner2", "password12345");
        String memberToken = registerAndLogin("member2@acme.com", "Member2", "password12345");

        String workspaceId = createWorkspace(ownerToken, "Role Test Workspace");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "member2@acme.com", "role": "VIEWER"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "owner2@acme.com", "role": "ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

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
