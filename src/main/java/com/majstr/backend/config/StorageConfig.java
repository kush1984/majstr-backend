package com.majstr.backend.config;

import com.majstr.backend.storage.LocalStorageService;
import com.majstr.backend.storage.S3StorageService;
import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Selects the single {@link StorageService} bean from {@code app.storage.kind}.
 * Building it imperatively (rather than {@code @ConditionalOnProperty}) keeps the
 * choice explicit and side-steps Spring Boot 4 auto-config surprises — the same
 * approach as {@link LocalizationConfig}. The S3 client is created only on the
 * {@code s3} branch, so local dev never needs R2 credentials.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageConfig {

    private final StorageProperties properties;

    @Bean
    public StorageService storageService() {
        if (properties.isS3()) {
            StorageProperties.S3 s3 = properties.s3();
            log.info("Storage backend: S3/R2 (bucket={})", s3.bucket());
            return new S3StorageService(s3Client(s3), s3.bucket());
        }
        log.info("Storage backend: local filesystem");
        return new LocalStorageService(properties);
    }

    private static S3Client s3Client(StorageProperties.S3 s3) {
        // R2 (and most S3-compatibles) require path-style addressing and use the
        // pseudo-region "auto". URLConnection HTTP client is set explicitly so the
        // SDK never errors on multiple HTTP implementations on the classpath.
        String region = (s3.region() == null || s3.region().isBlank()) ? "auto" : s3.region();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKeyId(), s3.secretAccessKey())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
