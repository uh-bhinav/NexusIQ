package com.nexusiq.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusiq.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

/**
 * Proves the append-only guarantee at the database layer (V4 migration trigger),
 * not just by omission from the repository interface — .claude/rules/database.md,
 * ADR-001. A real Postgres via Testcontainers, not H2, because the trigger is
 * PL/pgSQL (.claude/rules/testing.md).
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Sql(statements = "TRUNCATE TABLE audit_events CASCADE")
class AuditEventAppendOnlyIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void updatingAnAuditEvent_isRejectedByTheDatabase() {
        UUID id = insertOneAuditEvent();

        assertThatThrownBy(() -> {
                    entityManager
                            .createNativeQuery("UPDATE audit_events SET event_type = 'TAMPERED' WHERE id = :id")
                            .setParameter("id", id)
                            .executeUpdate();
                    entityManager.flush();
                })
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deletingAnAuditEvent_isRejectedByTheDatabase() {
        UUID id = insertOneAuditEvent();

        assertThatThrownBy(() -> {
                    entityManager
                            .createNativeQuery("DELETE FROM audit_events WHERE id = :id")
                            .setParameter("id", id)
                            .executeUpdate();
                    entityManager.flush();
                })
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("append-only");
    }

    private UUID insertOneAuditEvent() {
        UUID id = UUID.randomUUID();
        entityManager
                .createNativeQuery(
                        """
                        INSERT INTO audit_events (id, event_type, resource_type, occurred_at)
                        VALUES (:id, 'TEST_EVENT', 'TEST', now())
                        """)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.flush();
        return id;
    }
}
