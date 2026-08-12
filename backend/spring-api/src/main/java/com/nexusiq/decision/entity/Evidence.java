package com.nexusiq.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    private UUID id;

    @Column(name = "decision_run_id", nullable = false)
    private UUID decisionRunId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    private String claim;

    @Column(name = "evidence_text", nullable = false)
    private String evidenceText;

    @Column(name = "relevance_score", precision = 5, scale = 4)
    private BigDecimal relevanceScore;

    @Column(name = "citation_reference", length = 500)
    private String citationReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Evidence() {
        // JPA
    }

    public Evidence(
            UUID decisionRunId,
            UUID documentId,
            UUID chunkId,
            String evidenceText,
            BigDecimal relevanceScore,
            String citationReference) {
        this.id = UUID.randomUUID();
        this.decisionRunId = decisionRunId;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.evidenceText = evidenceText;
        this.relevanceScore = relevanceScore;
        this.citationReference = citationReference;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionRunId() {
        return decisionRunId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getChunkId() {
        return chunkId;
    }

    public String getClaim() {
        return claim;
    }

    public String getEvidenceText() {
        return evidenceText;
    }

    public BigDecimal getRelevanceScore() {
        return relevanceScore;
    }

    public String getCitationReference() {
        return citationReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
