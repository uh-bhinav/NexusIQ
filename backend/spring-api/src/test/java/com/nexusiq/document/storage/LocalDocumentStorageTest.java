package com.nexusiq.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No test existed for this class at all before — the path-traversal guard
 * (.claude/rules/security.md) and checksum computation were completely
 * unexercised. Pure filesystem unit test (JUnit @TempDir), no Docker
 * needed — LocalDocumentStorage has no Spring context dependency beyond
 * the plain StorageProperties record. */
class LocalDocumentStorageTest {

    private LocalDocumentStorage newStorage(java.nio.file.Path tempDir) {
        return new LocalDocumentStorage(new StorageProperties("local", tempDir.toString(), 25));
    }

    @Test
    void store_writesContentAndReturnsCorrectChecksumAndSize(@TempDir java.nio.file.Path tempDir) throws Exception {
        LocalDocumentStorage storage = newStorage(tempDir);
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        StoredFile result = storage.store(workspaceId, documentId, new ByteArrayInputStream(content));

        assertThat(result.storagePath()).isEqualTo(workspaceId + "/" + documentId + ".bin");
        assertThat(result.sizeBytes()).isEqualTo(content.length);
        assertThat(result.checksumSha256()).isEqualTo(sha256Hex(content));
    }

    @Test
    void retrieve_returnsExactlyWhatWasStored(@TempDir java.nio.file.Path tempDir) throws Exception {
        LocalDocumentStorage storage = newStorage(tempDir);
        byte[] content = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        StoredFile stored = storage.store(UUID.randomUUID(), UUID.randomUUID(), new ByteArrayInputStream(content));

        try (InputStream in = storage.retrieve(stored.storagePath())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void delete_removesTheFile_andSubsequentRetrieveFails(@TempDir java.nio.file.Path tempDir) throws Exception {
        LocalDocumentStorage storage = newStorage(tempDir);
        StoredFile stored = storage.store(
                UUID.randomUUID(), UUID.randomUUID(), new ByteArrayInputStream("bye".getBytes(StandardCharsets.UTF_8)));

        storage.delete(stored.storagePath());

        assertThatThrownBy(() -> storage.retrieve(stored.storagePath())).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void delete_onAlreadyMissingFile_doesNotThrow(@TempDir java.nio.file.Path tempDir) throws Exception {
        LocalDocumentStorage storage = newStorage(tempDir);

        storage.delete(UUID.randomUUID() + "/" + UUID.randomUUID() + ".bin");
        // No exception -> Files.deleteIfExists's idempotent-delete contract holds.
    }

    @Test
    void retrieve_pathEscapingStorageRootViaDotDot_isRejected(@TempDir java.nio.file.Path tempDir) {
        LocalDocumentStorage storage = newStorage(tempDir);

        assertThatThrownBy(() -> storage.retrieve("../../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the storage root");
    }

    @Test
    void retrieve_pathEscapingStorageRootViaAbsolutePath_isRejected(@TempDir java.nio.file.Path tempDir) {
        LocalDocumentStorage storage = newStorage(tempDir);

        assertThatThrownBy(() -> storage.retrieve("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the storage root");
    }

    @Test
    void delete_pathEscapingStorageRoot_isRejectedBeforeAnyFilesystemCall(@TempDir java.nio.file.Path tempDir) {
        // The guard applies uniformly across store/retrieve/delete — proven
        // once per public method rather than assuming resolveWithinBase's
        // one internal call site covers all three.
        LocalDocumentStorage storage = newStorage(tempDir);

        assertThatThrownBy(() -> storage.delete("../outside.bin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void store_forTwoDifferentDocuments_producesDistinctStoragePaths(@TempDir java.nio.file.Path tempDir)
            throws Exception {
        LocalDocumentStorage storage = newStorage(tempDir);
        UUID workspaceId = UUID.randomUUID();

        StoredFile first = storage.store(
                workspaceId, UUID.randomUUID(), new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        StoredFile second = storage.store(
                workspaceId, UUID.randomUUID(), new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));

        assertThat(first.storagePath()).isNotEqualTo(second.storagePath());
        try (InputStream in = storage.retrieve(first.storagePath())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("a");
        }
        try (InputStream in = storage.retrieve(second.storagePath())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("b");
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }
}
