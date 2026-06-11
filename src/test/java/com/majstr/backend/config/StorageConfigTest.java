package com.majstr.backend.config;

import com.majstr.backend.storage.LocalStorageService;
import com.majstr.backend.storage.S3StorageService;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch picks the right implementation from app.storage.kind. Building the
 * S3 client does not open a connection, so no network or live R2 is involved.
 */
class StorageConfigTest {

    @Test
    void kindLocal_buildsLocalStorageService() {
        StorageProperties props = new StorageProperties(
                "local",
                new StorageProperties.Local("./data/uploads"),
                null);

        StorageService bean = new StorageConfig(props).storageService();

        assertThat(bean).isInstanceOf(LocalStorageService.class);
    }

    @Test
    void kindMissing_defaultsToLocal() {
        StorageProperties props = new StorageProperties(
                null,
                new StorageProperties.Local("./data/uploads"),
                null);

        assertThat(new StorageConfig(props).storageService()).isInstanceOf(LocalStorageService.class);
    }

    @Test
    void kindS3_buildsS3StorageService() {
        StorageProperties props = new StorageProperties(
                "s3",
                new StorageProperties.Local("./data/uploads"),
                new StorageProperties.S3(
                        "https://accountid.r2.cloudflarestorage.com",
                        "access-key", "secret-key", "majstr-files", "auto"));

        StorageService bean = new StorageConfig(props).storageService();

        assertThat(bean).isInstanceOf(S3StorageService.class);
    }
}
