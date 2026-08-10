package com.nexusiq.workspace;

import com.nexusiq.workspace.entity.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    boolean existsBySlug(String slug);

    // Membership-scoped list: a user only ever sees workspaces they belong to.
    @Query(
            """
            select w from Workspace w
            join WorkspaceMember m on m.workspaceId = w.id
            where m.userId = :userId
            """)
    Page<Workspace> findAllForUser(@Param("userId") UUID userId, Pageable pageable);

    // Membership-scoped get: returns empty (-> 404) rather than fetching the
    // workspace and checking membership afterward, per .claude/rules/security.md.
    @Query(
            """
            select w from Workspace w
            join WorkspaceMember m on m.workspaceId = w.id
            where w.id = :workspaceId and m.userId = :userId
            """)
    Optional<Workspace> findByIdForUser(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);
}
