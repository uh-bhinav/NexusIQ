package com.nexusiq.document.dto;

import com.nexusiq.document.entity.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The JSON "metadata" part of a multipart upload. {@code supersedesDocumentId} is
 * optional: when present, the new document becomes the current version and the
 * referenced one is marked superseded (validated in-workspace by the service).
 */
public record CreateDocumentRequest(
        @NotBlank @Size(max = 500) String name,
        @NotNull DocumentType documentType,
        UUID supersedesDocumentId) {}
