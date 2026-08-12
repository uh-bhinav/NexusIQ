package com.nexusiq.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.common.exception.UpstreamUnavailableException;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain Mockito unit test (no Spring context): {@code @RestClientTest} no
 * longer exists in Boot 4.1's restructured test modules (confirmed by
 * searching every candidate module's jar — only the client-testing
 * {@code RestTestClient}/{@code TestRestTemplate} survived, which test your
 * own exposed endpoints, not an outbound call your service makes).
 * {@link MockRestServiceServer} itself is still in plain {@code spring-test}
 * and binds directly to a {@link RestClient.Builder}, so no Spring context is
 * needed at all here.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private WorkspaceAccessService accessService;

    private MockRestServiceServer mockServer;
    private KnowledgeService knowledgeService;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Mirrors config/JacksonConfig.java's snake_case customizer exactly:
        // in production, Boot wires the app's shared JsonMapper bean into the
        // injected RestClient.Builder automatically, but this test builds its
        // own RestClient with no Spring context, so that wiring needs
        // reproducing by hand — otherwise this test would pass against a
        // camelCase wire format that doesn't match what ai-service (snake_case
        // Pydantic) actually sends and expects, silently proving nothing about
        // the real cross-service contract.
        JsonMapper jsonMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.ALL_NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        RestClient.Builder builder =
                RestClient.builder().messageConverters(converters -> {
                    converters.removeIf(c -> c.getClass().getSimpleName().contains("Jackson"));
                    converters.add(0, new JacksonJsonHttpMessageConverter(jsonMapper));
                });
        mockServer = MockRestServiceServer.bindTo(builder).build();
        AiServiceProperties properties = new AiServiceProperties("http://ai-service-test", "unit-test-token");
        knowledgeService = new KnowledgeService(accessService, properties, builder);
    }

    @Test
    void search_checksMembership_thenCallsAiServiceWithTokenAndBody() {
        mockServer
                .expect(requestTo("http://ai-service-test/internal/search"))
                .andExpect(header("X-Internal-Service-Token", "unit-test-token"))
                .andExpect(jsonPath("$.query").value("vendor security"))
                .andExpect(jsonPath("$.workspace_id").value(workspaceId.toString()))
                .andRespond(withSuccess(
                        """
                        {"results": [], "query": "vendor security", "cached": false, "latency_ms": 12.5}
                        """,
                        MediaType.APPLICATION_JSON));

        var response = knowledgeService.search(workspaceId, requesterId, "vendor security", null);

        verify(accessService).requireMembership(workspaceId, requesterId);
        assertThat(response.query()).isEqualTo("vendor security");
        assertThat(response.results()).isEmpty();
        mockServer.verify();
    }

    @Test
    void search_membershipDenied_neverCallsAiService() {
        doThrow(new ResourceNotFoundException("not a member"))
                .when(accessService)
                .requireMembership(workspaceId, requesterId);

        assertThatThrownBy(() -> knowledgeService.search(workspaceId, requesterId, "q", null))
                .isInstanceOf(ResourceNotFoundException.class);

        mockServer.verify();
    }

    @Test
    void search_aiServiceError_throwsUpstreamUnavailable() {
        mockServer.expect(requestTo("http://ai-service-test/internal/search")).andRespond(withServerError());

        assertThatThrownBy(() -> knowledgeService.search(workspaceId, requesterId, "q", null))
                .isInstanceOf(UpstreamUnavailableException.class);
    }
}
