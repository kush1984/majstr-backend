package com.majstr.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File-storage configuration. {@code kind} selects the {@code StorageService}
 * implementation ({@code local} = filesystem for dev, {@code s3} = any
 * S3-compatible object store such as Cloudflare R2). The chosen bean is built
 * in {@link StorageConfig}; the {@code s3} block is only read when {@code kind=s3}
 * (credentials come from env, blank in dev).
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String kind,
        Local local,
        S3 s3
) {
    public record Local(String directory) {}

    public record S3(
            String endpoint,
            String accessKeyId,
            String secretAccessKey,
            String bucket,
            String region
    ) {}

    public boolean isS3() {
        return "s3".equalsIgnoreCase(kind);
    }
}
