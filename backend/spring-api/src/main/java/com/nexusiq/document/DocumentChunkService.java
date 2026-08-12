package com.nexusiq.document;

import com.nexusiq.common.PageResponse;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.common.exception.UpstreamUnavailableException;
import com.nexusiq.document.dto.ChunkResponse;
import com.nexusiq.knowledge.AiServiceProperties;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sync HTTP proxy to ai-service's chunk-browsing endpoint
 * (docs/API/API_DESIGN.md "GET .../documents/{documentId}/chunks" —
 * "paginated (citation resolution)"). {@code document_chunks} is Python-owned
 * (.claude/rules/database.md); Java never queries that table directly, same
 * boundary {@link com.nexusiq.knowledge.KnowledgeService} already
 * established for search. Reuses {@link AiServiceProperties} from that
 * package — it's generic "how to reach ai-service" config, not
 * knowledge-specific, so no reason to duplicate it here.
 */
@Service
@EnableConfigurationProperties(AiServiceProperties.class)
public class DocumentChunkService {

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessService accessService;
    private final AiServiceProperties properties;
    private final RestClient restClient;

    public DocumentChunkService(
            DocumentRepository documentRepository,
            WorkspaceAccessService accessService,
            AiServiceProperties properties,
            RestClient.Builder restClientBuilder) {
        this.documentRepository = documentRepository;
        this.accessService = accessService;
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    public PageResponse<ChunkResponse> listChunks(
            UUID workspaceId, UUID documentId, UUID requesterId, int page, int size) {
        accessService.requireMembership(workspaceId, requesterId);
        // 404 rather than fetch-then-check (.claude/rules/security.md) —
        // also guards against querying ai-service for a document that was
        // never uploaded to this workspace at all.
        documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        try {
            return restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/documents/{documentId}/chunks")
                            .queryParam("workspace_id", workspaceId)
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build(documentId))
                    .header("X-Internal-Service-Token", properties.internalToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<PageResponse<ChunkResponse>>() {});
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("AI service chunk request failed", e);
        }
    }
}
