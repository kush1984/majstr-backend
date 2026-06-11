package com.majstr.backend.storage;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores objects in an S3-compatible bucket (built for Cloudflare R2, works
 * with any S3 API). The object key mirrors the local impl exactly
 * ({@code prefix/uuid.ext}), so keys are portable between backends and the
 * {@code logoUrl} stored on a user keeps working after a migration.
 *
 * <p>Content type is kept as native S3 object metadata (no sidecar needed),
 * read back via HEAD. Reads stay served through {@code FileController}, so the
 * bucket needs no public-read policy — the backend streams the object.</p>
 *
 * <p>Built by {@link com.majstr.backend.config.StorageConfig} when
 * {@code app.storage.kind=s3}.</p>
 */
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final String bucket;

    @Override
    public StoredObject store(InputStream content, long size, String prefix, String extension, String contentType) {
        String safeExtension = (extension == null || extension.isBlank()) ? "bin" : extension.toLowerCase();
        String key = prefix + "/" + UUID.randomUUID() + "." + safeExtension;
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        s3.putObject(request.build(), RequestBody.fromInputStream(content, size));
        return new StoredObject(key, size, contentType);
    }

    @Override
    public Optional<InputStream> open(String objectKey) {
        try {
            // ResponseInputStream is an InputStream; caller closes it.
            return Optional.of(s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> contentType(String objectKey) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            String type = head.contentType();
            return (type == null || type.isBlank()) ? Optional.empty() : Optional.of(type);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String objectKey) {
        // S3 delete is idempotent — no error when the key is already gone.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }
}
