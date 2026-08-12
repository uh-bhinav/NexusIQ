package com.nexusiq.knowledge;

import com.nexusiq.common.exception.UpstreamUnavailableException;
import com.nexusiq.knowledge.dto.KnowledgeSearchResponse;
import com.nexusiq.knowledge.dto.SearchFiltersRequest;
import com.nexusiq.knowledge.dto.SearchRequest;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sync HTTP proxy to the AI service's search (.claude/rules/architecture.md:
 * "API -> AI service for search only: Sync HTTP, sub-second, user is
 * waiting"). Workspace membership is checked here, server-side, before the
 * `workspace_id` the AI service will trust is ever sent
 * (.claude/rules/security.md — never trust a client-supplied workspace_id).
 *
 * <p>The injected {@code RestClient.Builder} is forced onto HTTP/1.1 by
 * {@link com.nexusiq.config.RestClientConfig} — see that class for why.
 */
@Service
@EnableConfigurationProperties(AiServiceProperties.class)
public class KnowledgeService {

    private final WorkspaceAccessService accessService;
    private final AiServiceProperties properties;
    private final RestClient restClient;

    public KnowledgeService(
            WorkspaceAccessService accessService,
            AiServiceProperties properties,
            RestClient.Builder restClientBuilder) {
        this.accessService = accessService;
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    public KnowledgeSearchResponse search(
            UUID workspaceId, UUID requesterId, String query, List<String> documentTypes) {
        accessService.requireMembership(workspaceId, requesterId);

        SearchRequest body = new SearchRequest(workspaceId, query, new SearchFiltersRequest(documentTypes));
        try {
            return restClient
                    .post()
                    .uri("/internal/search")
                    .header("X-Internal-Service-Token", properties.internalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(KnowledgeSearchResponse.class);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("AI service search request failed", e);
        }
    }
}
