package com.nexusiq.audit;

import com.nexusiq.audit.entity.AuditEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately extends the bare marker interface, not CrudRepository/JpaRepository
 * — so delete/deleteById are simply not part of the Java API. The database trigger
 * (V4 migration) is the enforcement; this is defense in depth
 * (.claude/rules/database.md).
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findById(UUID id);

    Page<AuditEvent> findAllByWorkspaceIdOrderByOccurredAtDesc(UUID workspaceId, Pageable pageable);

    @Query(
            """
            select a from AuditEvent a
            where a.workspaceId = :workspaceId
              and a.resourceType = :resourceType and a.resourceId = :resourceId
            order by a.occurredAt desc
            """)
    Page<AuditEvent> findAllForResource(
            @Param("workspaceId") UUID workspaceId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            Pageable pageable);
}
