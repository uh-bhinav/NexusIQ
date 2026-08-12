package com.nexusiq.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusiq.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

/**
 * V5 proves the shape Python's SQLAlchemy layer will write into (embedding
 * vector(384), unique (document_id, chunk_index)) and that a raw cosine query
 * behaves sensibly — Phase 2 acceptance criteria 2 and 4
 * (docs/IMPLEMENTATION/ROADMAP.md). Java never writes this table in production;
 * this test writes to it directly only to prove the schema Python depends on.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Sql(statements = {
    "TRUNCATE TABLE document_chunks CASCADE",
    "TRUNCATE TABLE documents CASCADE",
    "TRUNCATE TABLE workspaces CASCADE",
    "TRUNCATE TABLE users CASCADE"
})
class DocumentChunksSchemaIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void chunkIndexIsUniquePerDocument_dbEnforced() {
        UUID documentId = seedWorkspaceAndDocument();
        insertChunk(documentId, 0, "first chunk", vectorLiteral(0.1f));

        assertThatThrownBy(() -> {
                    insertChunk(documentId, 0, "duplicate index", vectorLiteral(0.2f));
                    entityManager.flush();
                })
                .hasMessageContaining("uq_document_chunks_document_index");
    }

    @Test
    void cosineDistanceQuery_ranksTheNearestNeighbourFirst() {
        UUID documentId = seedWorkspaceAndDocument();
        insertChunk(documentId, 0, "close match", vectorLiteral(1.0f));
        insertChunk(documentId, 1, "far match", vectorLiteral(-1.0f));
        entityManager.flush();

        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager
                .createNativeQuery(
                        """
                        SELECT content, embedding <=> CAST(:query AS vector) AS distance
                        FROM document_chunks
                        ORDER BY distance ASC
                        LIMIT 2
                        """)
                .setParameter("query", vectorLiteral(1.0f))
                .getResultList();

        assertThat(results).hasSize(2);
        assertThat((String) results.get(0)[0]).isEqualTo("close match");
        assertThat((Number) results.get(0)[1]).extracting(Number::doubleValue).isEqualTo(0.0);
    }

    private UUID seedWorkspaceAndDocument() {
        UUID userId = UUID.randomUUID();
        entityManager
                .createNativeQuery(
                        """
                        INSERT INTO users (id, email, name, password_hash, role)
                        VALUES (:id, :email, 'Test User', 'x', 'ADMIN')
                        """)
                .setParameter("id", userId)
                .setParameter("email", userId + "@example.com")
                .executeUpdate();

        UUID workspaceId = UUID.randomUUID();
        entityManager
                .createNativeQuery(
                        "INSERT INTO workspaces (id, name, slug, created_by) VALUES (:id, 'ws', :slug, :createdBy)")
                .setParameter("id", workspaceId)
                .setParameter("slug", "ws-" + workspaceId)
                .setParameter("createdBy", userId)
                .executeUpdate();

        UUID documentId = UUID.randomUUID();
        entityManager
                .createNativeQuery(
                        """
                        INSERT INTO documents (id, workspace_id, name, document_type, uploaded_by)
                        VALUES (:id, :workspaceId, 'doc', 'OTHER', :uploadedBy)
                        """)
                .setParameter("id", documentId)
                .setParameter("workspaceId", workspaceId)
                .setParameter("uploadedBy", userId)
                .executeUpdate();

        entityManager.flush();
        return documentId;
    }

    private void insertChunk(UUID documentId, int chunkIndex, String content, String embedding) {
        entityManager
                .createNativeQuery(
                        """
                        INSERT INTO document_chunks
                            (document_id, workspace_id, chunk_index, content, embedding,
                             embedding_model, embedding_version)
                        SELECT :documentId, workspace_id, :chunkIndex, :content, CAST(:embedding AS vector),
                               'bge-small-en-v1.5', 1
                        FROM documents WHERE id = :documentId
                        """)
                .setParameter("documentId", documentId)
                .setParameter("chunkIndex", chunkIndex)
                .setParameter("content", content)
                .setParameter("embedding", embedding)
                .executeUpdate();
    }

    /** A 384-dim vector literal with one distinguishing component; rest zero. */
    private String vectorLiteral(float firstComponent) {
        StringBuilder sb = new StringBuilder("[").append(firstComponent);
        for (int i = 1; i < 384; i++) {
            sb.append(",0");
        }
        return sb.append("]").toString();
    }
}
