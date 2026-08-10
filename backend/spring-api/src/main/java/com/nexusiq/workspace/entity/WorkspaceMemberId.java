package com.nexusiq.workspace.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for workspace_members(workspace_id, user_id). */
public class WorkspaceMemberId implements Serializable {

    private UUID workspaceId;
    private UUID userId;

    public WorkspaceMemberId() {
        // JPA
    }

    public WorkspaceMemberId(UUID workspaceId, UUID userId) {
        this.workspaceId = workspaceId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkspaceMemberId that)) return false;
        return Objects.equals(workspaceId, that.workspaceId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, userId);
    }
}
