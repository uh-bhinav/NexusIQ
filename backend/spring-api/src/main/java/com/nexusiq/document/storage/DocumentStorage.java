package com.nexusiq.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Abstracts where document bytes live. Business logic never touches a raw
 * filesystem path (.claude/rules/security.md) — every implementation is
 * responsible for generating its own opaque storage identifier and rejecting
 * anything derived from client-supplied input (path traversal defence lives
 * here, once, rather than in every caller).
 */
public interface DocumentStorage {

    /** Streams {@code content} to storage under a generated name, computing its checksum in the same pass. */
    StoredFile store(UUID workspaceId, UUID documentId, InputStream content) throws IOException;

    InputStream retrieve(String storagePath) throws IOException;

    void delete(String storagePath) throws IOException;
}
