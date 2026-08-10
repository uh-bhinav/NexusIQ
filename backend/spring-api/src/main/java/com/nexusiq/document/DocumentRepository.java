package com.nexusiq.document;

import com.nexusiq.document.entity.Document;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);

    // Workspace-scoped get: empty (-> 404) rather than fetch-then-check, so a
    // document in another tenant's workspace is never disclosed.
    Optional<Document> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
