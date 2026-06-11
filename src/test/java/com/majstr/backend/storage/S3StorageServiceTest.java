package com.majstr.backend.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    private static final String BUCKET = "majstr-files";

    @Mock private S3Client s3;
    private S3StorageService storage;

    private S3StorageService service() {
        if (storage == null) {
            storage = new S3StorageService(s3, BUCKET);
        }
        return storage;
    }

    @Test
    void store_putsObjectUnderPrefixedUuidKeyAndReturnsDescriptor() throws IOException {
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(PutObjectResponse.builder().build());
        byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = service().store(
                new ByteArrayInputStream(bytes), bytes.length, "logos", "PNG", "image/png");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        // Key = prefix/uuid.ext, extension lower-cased.
        assertThat(req.getValue().key()).matches("logos/[0-9a-f-]{36}\\.png");
        assertThat(req.getValue().contentType()).isEqualTo("image/png");
        // Returned descriptor mirrors the request.
        assertThat(stored.key()).isEqualTo(req.getValue().key());
        assertThat(stored.size()).isEqualTo(bytes.length);
        assertThat(stored.contentType()).isEqualTo("image/png");
    }

    @Test
    void store_fallsBackToBinExtensionWhenMissing() throws IOException {
        given(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willReturn(PutObjectResponse.builder().build());

        StoredObject stored = service().store(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4, "misc", null, null);

        assertThat(stored.key()).matches("misc/[0-9a-f-]{36}\\.bin");
    }

    @Test
    void open_returnsStreamForExistingKey() throws IOException {
        byte[] bytes = "logo-content".getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        given(s3.getObject(any(GetObjectRequest.class))).willReturn(response);

        Optional<InputStream> opened = service().open("logos/abc.png");

        assertThat(opened).isPresent();
        assertThat(opened.get().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void open_returnsEmptyWhenKeyMissing() throws IOException {
        given(s3.getObject(any(GetObjectRequest.class))).willThrow(NoSuchKeyException.builder().build());

        assertThat(service().open("logos/missing.png")).isEmpty();
    }

    @Test
    void contentType_readsFromHeadObject() {
        given(s3.headObject(any(HeadObjectRequest.class)))
                .willReturn(HeadObjectResponse.builder().contentType("image/jpeg").build());

        assertThat(service().contentType("logos/abc.jpg")).contains("image/jpeg");
    }

    @Test
    void contentType_emptyWhenKeyMissing() {
        given(s3.headObject(any(HeadObjectRequest.class))).willThrow(NoSuchKeyException.builder().build());

        assertThat(service().contentType("logos/missing.jpg")).isEmpty();
    }

    @Test
    void delete_issuesDeleteObjectForKey() throws IOException {
        service().delete("logos/abc.png");

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("logos/abc.png");
    }
}
