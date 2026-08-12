package com.nexusiq.document.storage;

/** Result of writing an upload to storage: where it landed and its integrity fingerprint. */
public record StoredFile(String storagePath, String checksumSha256, long sizeBytes) {}
