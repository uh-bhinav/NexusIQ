package com.nexusiq.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import com.nexusiq.user.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * End to end through the real filter chain (security, correlation id, exception
 * handling) — Phase 1 acceptance criteria 1, 2, 7, 9
 * (docs/IMPLEMENTATION/ROADMAP.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events, workspace_members, workspaces, documents, users CASCADE")
class AuthFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLogin_returnsAJwtAndMeResolvesTheUser() throws Exception {
        String email = "alice@example.com";
        String registerBody =
                """
                {"email": "%s", "name": "Alice", "password": "correct-horse-battery"}
                """
                        .formatted(email);

        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var registerJson = objectMapper.readTree(registerResponse);
        assertThat(registerJson.get("access_token").asString()).isNotBlank();
        assertThat(registerJson.get("user").get("role").asString()).isEqualTo(Role.ANALYST.name());

        String loginBody =
                """
                {"email": "%s", "password": "correct-horse-battery"}
                """
                        .formatted(email);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = objectMapper.readTree(loginResponse).get("access_token").asString();
        assertThat(accessToken).isNotBlank();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.workspaces").isArray());
    }

    @Test
    void login_withWrongPassword_returns401WithStandardEnvelope() throws Exception {
        register("bob@example.com", "Bob", "correct-password-123");

        String badLogin =
                """
                {"email": "bob@example.com", "password": "totally-wrong"}
                """;

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(badLogin))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.request_id").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void protectedEndpoint_withNoToken_returns401WithStandardEnvelopeAndRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.request_id").exists())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void protectedEndpoint_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_withBlankFields_returns400WithFieldDetails() throws Exception {
        String invalidBody =
                """
                {"email": "not-an-email", "name": "", "password": "short"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1))));
    }

    @Test
    void register_withDuplicateEmail_returns409() throws Exception {
        register("dup@example.com", "First", "password12345");

        String duplicate =
                """
                {"email": "dup@example.com", "name": "Second", "password": "password12345"}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void everyResponse_carriesTheSameCorrelationId_whenOneIsSupplied() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Correlation-Id", "11111111-1111-1111-1111-111111111111"))
                .andExpect(header().string("X-Correlation-Id", "11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.request_id").value("11111111-1111-1111-1111-111111111111"));
    }

    private void register(String email, String name, String password) throws Exception {
        String body =
                """
                {"email": "%s", "name": "%s", "password": "%s"}
                """
                        .formatted(email, name, password);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
