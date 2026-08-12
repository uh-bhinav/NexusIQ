package com.nexusiq.document;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.CorrelationIdFilter;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.document.dto.CreateDocumentRequest;
import com.nexusiq.document.dto.DocumentResponse;
import com.nexusiq.document.entity.Document;
import com.nexusiq.document.mapper.DocumentMapper;
import com.nexusiq.document.storage.DocumentStorage;
import com.nexusiq.document.storage.FileTypeValidator;
import com.nexusiq.document.storage.StoredFile;
import com.nexusiq.messaging.DocumentUploadedEvent;
import com.nexusiq.messaging.DocumentUploadedPayload;
import com.nexusiq.messaging.EventEnvelope;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    /** Enough to see the real magic bytes of any format we accept; cheap to hold in memory. */
    private static final int HEADER_SNIFF_BYTES = 4096;

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessService accessService;
    private final DocumentMapper mapper;
    private final AuditService auditService;
    private final DocumentStorage documentStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final TraceContextPropagation traceContext;

    public DocumentService(
            DocumentRepository documentRepository,
            WorkspaceAccessService accessService,
            DocumentMapper mapper,
            AuditService auditService,
            DocumentStorage documentStorage,
            ApplicationEventPublisher eventPublisher,
            TraceContextPropagation traceContext) {
        this.documentRepository = documentRepository;
        this.accessService = accessService;
        this.mapper = mapper;
        this.auditService = auditService;
        this.documentStorage = documentStorage;
        this.eventPublisher = eventPublisher;
        this.traceContext = traceContext;
    }

    @Transactional
    public DocumentResponse upload(
            UUID workspaceId, UUID requesterId, CreateDocumentRequest request, MultipartFile file) {
        accessService.requireMembership(workspaceId, requesterId);

        String filename = file.getOriginalFilename();
        FileTypeValidator.Format format = FileTypeValidator.detectFromExtension(filename);
        byte[] bytes = readAllBytes(file);
        FileTypeValidator.validate(format, Arrays.copyOf(bytes, Math.min(bytes.length, HEADER_SNIFF_BYTES)));

        Document document = new Document(workspaceId, request.name(), request.documentType(), requesterId);

        if (request.supersedesDocumentId() != null) {
            Document previous = documentRepository
                    .findByIdAndWorkspaceId(request.supersedesDocumentId(), workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
            document.supersede(previous);
        }

        StoredFile stored = storeBytes(workspaceId, document.getId(), bytes);
        document.recordUpload(filename, stored.storagePath(), file.getContentType(), stored.sizeBytes(), stored.checksumSha256());

        document = documentRepository.save(document);

        auditService.record(workspaceId, requesterId, "DOCUMENT_UPLOADED", "DOCUMENT", document.getId());

        publishUploadedEvent(workspaceId, document, stored, filename);

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

    private void publishUploadedEvent(UUID workspaceId, Document document, StoredFile stored, String filename) {
        DocumentUploadedPayload payload = new DocumentUploadedPayload(
                document.getId(),
                document.getDocumentType().name(),
                stored.storagePath(),
                document.getContentType(),
                stored.sizeBytes(),
                stored.checksumSha256(),
                filename);
        UUID correlationId = parseCorrelationId(CorrelationIdFilter.currentOrNew());
        EventEnvelope<DocumentUploadedPayload> envelope = EventEnvelope.newEvent(
                "DOCUMENT_UPLOADED", workspaceId, correlationId, traceContext.currentTraceparent(), payload);
        eventPublisher.publishEvent(new DocumentUploadedEvent(envelope));
    }

    private static UUID parseCorrelationId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }

    private StoredFile storeBytes(UUID workspaceId, UUID documentId, byte[] bytes) {
        try {
            return documentStorage.store(workspaceId, documentId, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded document", e);
        }
    }

    private static byte[] readAllBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded document", e);
        }
    }
}
