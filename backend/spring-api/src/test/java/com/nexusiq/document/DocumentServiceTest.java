package com.nexusiq.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusiq.audit.AuditService;
import com.nexusiq.common.exception.ResourceNotFoundException;
import com.nexusiq.document.dto.CreateDocumentRequest;
import com.nexusiq.document.entity.Document;
import com.nexusiq.document.entity.DocumentType;
import com.nexusiq.document.mapper.DocumentMapper;
import com.nexusiq.workspace.WorkspaceAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private AuditService auditService;

    private DocumentService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentService(documentRepository, accessService, new DocumentMapper(), auditService);
    }

    @Test
    void create_requiresWorkspaceMembership_beforeCreatingTheRow() {
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(workspaceId, requesterId, new CreateDocumentRequest("Policy", DocumentType.SECURITY_POLICY));

        verify(accessService).requireMembership(workspaceId, requesterId);
        verify(auditService)
                .record(org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.eq(requesterId),
                        org.mockito.ArgumentMatchers.eq("DOCUMENT_CREATED"), org.mockito.ArgumentMatchers.eq("DOCUMENT"),
                        any());
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
