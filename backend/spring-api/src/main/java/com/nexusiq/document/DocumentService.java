package com.nexusiq.document;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.document.dto.CreateDocumentRequest;
import com.nexusiq.document.dto.DocumentResponse;
import com.nexusiq.document.entity.Document;
import com.nexusiq.document.mapper.DocumentMapper;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessService accessService;
    private final DocumentMapper mapper;
    private final AuditService auditService;

    public DocumentService(
            DocumentRepository documentRepository,
            WorkspaceAccessService accessService,
            DocumentMapper mapper,
            AuditService auditService) {
        this.documentRepository = documentRepository;
        this.accessService = accessService;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Transactional
    public DocumentResponse create(UUID workspaceId, UUID requesterId, CreateDocumentRequest request) {
        accessService.requireMembership(workspaceId, requesterId);

        Document document = new Document(workspaceId, request.name(), request.documentType(), requesterId);
        document = documentRepository.save(document);

        auditService.record(workspaceId, requesterId, "DOCUMENT_CREATED", "DOCUMENT", document.getId());

        return mapper.toResponse(document);
    }

    @Transactional(readOnly = true)
    public Page<DocumentResponse> list(UUID workspaceId, UUID requesterId, Pageable pageable) {
        accessService.requireMembership(workspaceId, requesterId);
        return documentRepository.findAllByWorkspaceId(workspaceId, pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID documentId, UUID workspaceId, UUID requesterId) {
        accessService.requireMembership(workspaceId, requesterId);
        Document document = documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        return mapper.toResponse(document);
    }

    @Transactional
    public void delete(UUID documentId, UUID workspaceId, UUID requesterId) {
        accessService.requireMembership(workspaceId, requesterId);
        Document document = documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        documentRepository.delete(document);

        auditService.record(workspaceId, requesterId, "DOCUMENT_DELETED", "DOCUMENT", documentId);
    }
}
