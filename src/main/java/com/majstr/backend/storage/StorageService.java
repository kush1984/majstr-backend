package com.majstr.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Abstraction over the file storage backend. Today there is one impl,
 * {@link LocalStorageService}, that writes to the local filesystem; a future
 * S3/R2 impl can be dropped in with no service-layer changes.
 *
 * <p>The "object key" is an opaque identifier (e.g. {@code logos/abcd-ef.png}).
 * Callers never construct it manually — {@link #store} returns it.</p>
 */
public interface StorageService {

    /** Persists the content and returns a stored object descriptor. */
    StoredObject store(InputStream content, long size, String prefix, String extension, String contentType) throws IOException;

    /** Opens the stored content for reading, or empty if the key is unknown. */
    Optional<InputStream> open(String objectKey) throws IOException;

    /** Returns the content type recorded at upload, or empty if unknown. */
    Optional<String> contentType(String objectKey);

    /** Deletes the object, no-op if it does not exist. */
    void delete(String objectKey) throws IOException;
}
