package com.nexusiq.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Filesystem-backed {@link DocumentStorage} for local/Compose deployment
 * (ADR-010, $0 infra). Storage keys are always {@code {workspaceId}/{documentId}}
 * — server-generated UUIDs only, so there is nothing derived from client input
 * for a path-traversal attempt to exploit (.claude/rules/security.md).
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class LocalDocumentStorage implements DocumentStorage {

    private final Path baseDir;

    public LocalDocumentStorage(StorageProperties properties) {
        this.baseDir = Path.of(properties.localPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create document storage directory: " + baseDir, e);
        }
    }

    @Override
    public StoredFile store(UUID workspaceId, UUID documentId, InputStream content) throws IOException {
        String storagePath = workspaceId + "/" + documentId + ".bin";
        Path target = resolveWithinBase(storagePath);
        Files.createDirectories(target.getParent());

        MessageDigest digest = sha256();
        try (DigestInputStream digestStream = new DigestInputStream(content, digest);
                OutputStream out = Files.newOutputStream(
                        target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long sizeBytes = digestStream.transferTo(out);
            String checksum = HexFormat.of().formatHex(digest.digest());
            return new StoredFile(storagePath, checksum, sizeBytes);
        }
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        return Files.newInputStream(resolveWithinBase(storagePath));
    }

    @Override
    public void delete(String storagePath) throws IOException {
        Files.deleteIfExists(resolveWithinBase(storagePath));
    }

    private Path resolveWithinBase(String storagePath) {
        Path resolved = baseDir.resolve(storagePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Storage path escapes the storage root: " + storagePath);
        }
        return resolved;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Guaranteed present on every JVM per the Java platform spec.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
