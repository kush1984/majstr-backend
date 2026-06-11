package com.majstr.backend.storage;

import com.majstr.backend.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores objects under a single root directory. The object key encodes
 * both the prefix bucket ({@code logos/}, {@code pdfs/}, …) and the
 * UUID-named file. Content type is parked in a sidecar {@code .meta}
 * file because the filesystem doesn't carry MIME natively.
 *
 * <p>Instantiated by {@link com.majstr.backend.config.StorageConfig} when
 * {@code app.storage.kind=local} (the default) — not component-scanned, so the
 * S3 impl can replace it without two beans colliding.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageProperties properties;
    private Path root;

    @PostConstruct
    void init() throws IOException {
        root = Path.of(properties.local().directory()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Local storage root: {}", root);
    }

    @Override
    public StoredObject store(InputStream content, long size, String prefix, String extension, String contentType) throws IOException {
        String safeExtension = (extension == null || extension.isBlank()) ? "bin" : extension.toLowerCase();
        String filename = UUID.randomUUID() + "." + safeExtension;
        String key = prefix + "/" + filename;
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        long actualSize = Files.size(target);
        if (contentType != null && !contentType.isBlank()) {
            Files.writeString(target.resolveSibling(filename + ".meta"), contentType);
        }
        return new StoredObject(key, actualSize, contentType);
    }

    @Override
    public Optional<InputStream> open(String objectKey) throws IOException {
        Path file = resolve(objectKey);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(Files.newInputStream(file));
    }

    @Override
    public Optional<String> contentType(String objectKey) {
        Path meta = resolve(objectKey + ".meta");
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(meta).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        Path file = resolve(objectKey);
        Path meta = resolve(objectKey + ".meta");
        try {
            Files.delete(file);
        } catch (NoSuchFileException ignored) {
            // already gone
        }
        try {
            Files.delete(meta);
        } catch (NoSuchFileException ignored) {
            // no meta sidecar
        }
    }

    /**
     * Resolves a key to an absolute path and refuses anything that climbs
     * out of the storage root — basic protection against {@code ../} keys.
     */
    private Path resolve(String objectKey) {
        Path candidate = root.resolve(objectKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Object key escapes storage root: " + objectKey);
        }
        return candidate;
    }
}
