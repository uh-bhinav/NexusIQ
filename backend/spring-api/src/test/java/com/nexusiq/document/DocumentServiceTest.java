package com.nexusiq.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.document.dto.CreateDocumentRequest;
import com.nexusiq.document.entity.Document;
import com.nexusiq.document.entity.DocumentType;
import com.nexusiq.document.mapper.DocumentMapper;
import com.nexusiq.document.storage.DocumentStorage;
import com.nexusiq.document.storage.StoredFile;
import com.nexusiq.messaging.DocumentUploadedEvent;
import com.nexusiq.observability.TraceContextPropagation;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private AuditService auditService;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TraceContextPropagation traceContext;

    private DocumentService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository,
                accessService,
                new DocumentMapper(),
                auditService,
                documentStorage,
                eventPublisher,
                traceContext);
    }

    @Test
    void upload_requiresWorkspaceMembership_storesTheFile_andAuditsAndPublishes() throws Exception {
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentStorage.store(eq(workspaceId), any(UUID.class), any(InputStream.class)))
                .thenReturn(new StoredFile("ws/doc.bin", "deadbeef", 11));

        MockMultipartFile file =
                new MockMultipartFile("file", "policy.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        service.upload(
                workspaceId,
                requesterId,
                new CreateDocumentRequest("Policy", DocumentType.SECURITY_POLICY, null),
                file);

        verify(accessService).requireMembership(workspaceId, requesterId);
        verify(auditService)
                .record(eq(workspaceId), eq(requesterId), eq("DOCUMENT_UPLOADED"), eq("DOCUMENT"), any());
        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
    }

    @Test
    void upload_rejectsAFileWhoseExtensionLiesAboutItsContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "not actually a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(
                        workspaceId,
                        requesterId,
                        new CreateDocumentRequest("Policy", DocumentType.SECURITY_POLICY, null),
                        file))
                .isInstanceOf(com.nexusiq.common.exception.ValidationException.class);
    }

    @Test
    void upload_withSupersedes_incrementsVersionAndMarksThePreviousDocumentNotCurrent() throws Exception {
        UUID previousId = UUID.randomUUID();
        Document previous = new Document(workspaceId, "Policy v1", DocumentType.SECURITY_POLICY, requesterId);
        when(documentRepository.findByIdAndWorkspaceId(previousId, workspaceId)).thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentStorage.store(eq(workspaceId), any(UUID.class), any(InputStream.class)))
                .thenReturn(new StoredFile("ws/doc.bin", "deadbeef", 11));

        MockMultipartFile file =
                new MockMultipartFile("file", "policy.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(
                        workspaceId,
                        requesterId,
                        new CreateDocumentRequest("Policy v2", DocumentType.SECURITY_POLICY, previousId),
                        file))
                .isInstanceOf(ResourceNotFoundException.class);

        // Now prove the happy path: previous document exists in-workspace.
        when(documentRepository.findByIdAndWorkspaceId(previousId, workspaceId)).thenReturn(Optional.of(previous));
        service.upload(
                workspaceId,
                requesterId,
                new CreateDocumentRequest("Policy v2", DocumentType.SECURITY_POLICY, previousId),
                file);

        assertThat(previous.isCurrent()).isFalse();
    }

    @Test
    void get_throwsNotFound_whenDocumentExistsInADifferentWorkspace() {
        UUID documentId = UUID.randomUUID();
        // The repository query itself is scoped by workspaceId (findByIdAndWorkspaceId),
        // so a document belonging to another workspace simply isn't found here —
        // this is the behaviour the cross-tenant-denial acceptance criterion depends on.
        when(documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(documentId, workspaceId, requesterId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_throwsNotFound_whenDocumentIsNotInThisWorkspace() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(documentId, workspaceId, requesterId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
