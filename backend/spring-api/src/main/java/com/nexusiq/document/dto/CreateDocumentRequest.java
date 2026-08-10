package com.nexusiq.document.dto;

import com.nexusiq.document.entity.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Phase 1: metadata only, no file content. Phase 2 replaces/extends this endpoint
 * with a real multipart upload (docs/IMPLEMENTATION/ROADMAP.md Phase 2).
 */
public record CreateDocumentRequest(
        @NotBlank @Size(max = 500) String name, @NotNull DocumentType documentType) {}
