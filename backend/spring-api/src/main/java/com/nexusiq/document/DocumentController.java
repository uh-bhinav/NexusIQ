package com.nexusiq.document;

import com.nexusiq.common.PageResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nested under the workspace path deliberately, so every lookup filters on
 * workspace_id in SQL rather than fetching by document id alone and checking
 * membership afterward (.claude/rules/security.md, .claude/rules/database.md).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentResponse> create(
            @PathVariable UUID workspaceId, @Valid @RequestBody CreateDocumentRequest request) {
        DocumentResponse created = documentService.create(workspaceId, CurrentUser.id(), request);
        return ResponseEntity.created(
                        URI.create("/api/v1/workspaces/" + workspaceId + "/documents/" + created.id()))
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
}
