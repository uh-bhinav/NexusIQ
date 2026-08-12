package com.nexusiq.document;

import com.nexusiq.common.PageResponse;
import com.nexusiq.document.dto.ChunkResponse;
import com.nexusiq.document.dto.CreateDocumentRequest;
import com.nexusiq.document.dto.DocumentResponse;
import com.nexusiq.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Nested under the workspace path deliberately, so every lookup filters on
 * workspace_id in SQL rather than fetching by document id alone and checking
 * membership afterward (.claude/rules/security.md, .claude/rules/database.md).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentChunkService documentChunkService;

    public DocumentController(DocumentService documentService, DocumentChunkService documentChunkService) {
        this.documentService = documentService;
        this.documentChunkService = documentChunkService;
    }

    /**
     * Multipart upload: a "file" part (the document bytes) and a "metadata" part
     * (JSON body matching {@link CreateDocumentRequest}). Returns 202 — ingestion
     * happens asynchronously via document.uploaded (.claude/rules/backend-java.md
     * "long-running work returns 202, never blocks").
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID workspaceId,
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid CreateDocumentRequest metadata) {
        DocumentResponse created = documentService.upload(workspaceId, CurrentUser.id(), metadata, file);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/workspaces/" + workspaceId + "/documents/" + created.id()))
                .body(created);
    }

    @GetMapping
    public PageResponse<DocumentResponse> list(
            @PathVariable UUID workspaceId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(documentService.list(workspaceId, CurrentUser.id(), pageable), r -> r);
    }

    @GetMapping("/{documentId}")
    public DocumentResponse get(@PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        return documentService.get(documentId, workspaceId, CurrentUser.id());
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        documentService.delete(documentId, workspaceId, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    /** Citation resolution (docs/API/API_DESIGN.md) — the Decision Detail
     * page's evidence links resolve here, to the exact chunk, not just the
     * document. Proxies to ai-service; {@code document_chunks} is
     * Python-owned (.claude/rules/database.md). */
    @GetMapping("/{documentId}/chunks")
    public PageResponse<ChunkResponse> chunks(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @PageableDefault(size = 20) Pageable pageable) {
        return documentChunkService.listChunks(
                workspaceId, documentId, CurrentUser.id(), pageable.getPageNumber(), pageable.getPageSize());
    }
}
