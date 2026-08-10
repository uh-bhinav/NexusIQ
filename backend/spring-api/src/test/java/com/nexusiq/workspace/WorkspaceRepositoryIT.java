package com.nexusiq.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusiq.TestcontainersConfiguration;
import com.nexusiq.user.entity.Role;
import com.nexusiq.user.entity.User;
import com.nexusiq.workspace.entity.Workspace;
import com.nexusiq.workspace.entity.WorkspaceMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.jdbc.Sql;

/**
 * Proves the custom membership-scoped queries actually scope by membership at
 * the SQL level — this is what acceptance criterion 4 (cross-tenant denial)
 * depends on (.claude/rules/database.md, .claude/rules/security.md).
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events, workspace_members, workspaces, documents, users CASCADE")
class WorkspaceRepositoryIT {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void findAllForUser_onlyReturnsWorkspacesTheUserBelongsTo() {
        User alice = persist(new User("alice@example.com", "Alice", "hash", Role.ANALYST));
        User bob = persist(new User("bob@example.com", "Bob", "hash", Role.ANALYST));

        Workspace workspaceA = persist(new Workspace("Workspace A", "workspace-a", null, alice.getId()));
        Workspace workspaceB = persist(new Workspace("Workspace B", "workspace-b", null, bob.getId()));

        memberRepository.save(new WorkspaceMember(workspaceA.getId(), alice.getId(), Role.ADMIN));
        memberRepository.save(new WorkspaceMember(workspaceB.getId(), bob.getId(), Role.ADMIN));
        entityManager.flush();
        entityManager.clear();

        var alicesWorkspaces = workspaceRepository.findAllForUser(alice.getId(), PageRequest.of(0, 20));

        assertThat(alicesWorkspaces.getContent()).extracting(Workspace::getId).containsExactly(workspaceA.getId());
    }

    @Test
    void findByIdForUser_returnsEmpty_forANonMember() {
        User alice = persist(new User("alice2@example.com", "Alice", "hash", Role.ANALYST));
        User bob = persist(new User("bob2@example.com", "Bob", "hash", Role.ANALYST));

        Workspace workspaceA = persist(new Workspace("Workspace A2", "workspace-a2", null, alice.getId()));
        memberRepository.save(new WorkspaceMember(workspaceA.getId(), alice.getId(), Role.ADMIN));
        // bob is never added as a member of workspaceA.
        entityManager.flush();
        entityManager.clear();

        var result = workspaceRepository.findByIdForUser(workspaceA.getId(), bob.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void findByIdForUser_returnsTheWorkspace_forAnActualMember() {
        User alice = persist(new User("alice3@example.com", "Alice", "hash", Role.ANALYST));
        Workspace workspaceA = persist(new Workspace("Workspace A3", "workspace-a3", null, alice.getId()));
        memberRepository.save(new WorkspaceMember(workspaceA.getId(), alice.getId(), Role.ADMIN));
        entityManager.flush();
        entityManager.clear();

        var result = workspaceRepository.findByIdForUser(workspaceA.getId(), alice.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(workspaceA.getId());
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
