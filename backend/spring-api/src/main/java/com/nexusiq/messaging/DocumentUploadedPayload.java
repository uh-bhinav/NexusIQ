package com.nexusiq.messaging;

import java.util.UUID;

/** Payload for {@code document.uploaded}. IDs and facts only — never file bytes. */
public record DocumentUploadedPayload(
        UUID documentId,
        String documentType,
        String storagePath,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String originalFilename) {}
