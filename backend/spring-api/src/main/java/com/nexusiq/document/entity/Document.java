package com.nexusiq.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * `storage_path`/`checksum_sha256`/`content_type`/`size_bytes` are set on upload
 * (Phase 2); `chunk_count` and `status` transitions are driven by the
 * `document.processed`/`document.failed` Kafka consumers once the AI service
 * finishes ingestion (ADR-003, ADR-004).
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "supersedes_document_id")
    private UUID supersedesDocumentId;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "storage_path", length = 1000)
    private String storagePath;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // JPA
    }

    public Document(UUID workspaceId, String name, DocumentType documentType, UUID uploadedBy) {
        this.id = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.name = name;
        this.documentType = documentType;
        this.uploadedBy = uploadedBy;
    }

    /** Records the result of a successful upload+store; status stays/becomes UPLOADED. */
    public void recordUpload(
            String originalFilename, String storagePath, String contentType, long sizeBytes, String checksumSha256) {
        this.originalFilename = originalFilename;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.status = DocumentStatus.UPLOADED;
    }

    /** Marks this document as the new current version, superseding {@code previous}. */
    public void supersede(Document previous) {
        this.supersedesDocumentId = previous.id;
        this.version = previous.version + 1;
        previous.current = false;
    }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
    }

    public void markReady(int chunkCount) {
        this.status = DocumentStatus.READY;
        this.chunkCount = chunkCount;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        this.failureReason = reason;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getName() {
        return name;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public int getVersion() {
        return version;
    }

    public UUID getSupersedesDocumentId() {
        return supersedesDocumentId;
    }

    public boolean isCurrent() {
        return current;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
