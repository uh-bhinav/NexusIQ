package com.nexusiq.workspace.mapper;

import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.dto.MemberResponse;
import com.nexusiq.workspace.dto.WorkspaceResponse;
import com.nexusiq.workspace.dto.WorkspaceSummaryResponse;
import com.nexusiq.workspace.entity.Workspace;
import com.nexusiq.workspace.entity.WorkspaceMember;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceMapper {

    public WorkspaceResponse toResponse(Workspace w) {
        return new WorkspaceResponse(
                w.getId(), w.getName(), w.getSlug(), w.getDescription(), w.getCreatedBy(), w.getCreatedAt(), w.getUpdatedAt());
    }

    public WorkspaceSummaryResponse toSummary(Workspace w, WorkspaceMember membership) {
        return new WorkspaceSummaryResponse(w.getId(), w.getName(), w.getSlug(), membership.getRole());
    }

    public MemberResponse toMemberResponse(WorkspaceMember member, User user) {
        return new MemberResponse(user.getId(), user.getEmail(), user.getName(), member.getRole(), member.getJoinedAt());
    }
}
