package com.majstr.backend.service;

import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectMessageFile;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Attachments arriving from a stranger through the master's public form.
 *
 * <p>Everything asserted here is a boundary rather than a behaviour. The uploader controls the
 * filename, the declared Content-Type and the bytes, and all three have to be treated as hostile: a
 * {@code .png} that is really something else must be refused, a name carrying a path must be reduced to
 * a label, and neither the count nor the size limits may be exceeded.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageFileServiceTest {

    @Mock ProjectMessageFileRepository fileRepository;
    @Mock StorageService storage;
    @InjectMocks MessageFileService service;

    private static final byte[] PNG_HEADER =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, (byte) 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] PDF_HEADER = "%PDF-1.7\nstuff".getBytes(StandardCharsets.UTF_8);

    private ProjectMessage message() {
        return ProjectMessage.builder()
                .id(UUID.randomUUID())
                .authorName("Постачальник")
                .message("Рахунок")
                .files(new ArrayList<>())
                .build();
    }

    private void storageAccepts() throws IOException {
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willAnswer(i -> new StoredObject("messages/" + UUID.randomUUID() + "." + i.getArgument(3),
                        i.getArgument(1), i.getArgument(4)));
        given(fileRepository.save(any(ProjectMessageFile.class))).willAnswer(i -> i.getArgument(0));
    }

    private static MultipartFile file(String name, String declaredType, byte[] content) {
        return new MockMultipartFile("files", name, declaredType, content);
    }

    // =============================================================================================

    @Test
    void storesAPhotoAndAPdf() throws IOException {
        storageAccepts();
        ProjectMessage m = message();

        List<ProjectMessageFile> saved = service.attach(m, List.of(
                file("wall.png", "image/png", PNG_HEADER),
                file("invoice.pdf", "application/pdf", PDF_HEADER)));

        assertThat(saved).extracting(ProjectMessageFile::getContentType)
                .containsExactly("image/png", "application/pdf");
        assertThat(m.getFiles())
                .as("повідомлення мусить бачити свої ж вкладення в цьому ж викликі")
                .hasSize(2);
    }

    @Test
    void refusesAFileWhoseBytesAreNotWhatItsNameClaims() {
        // The whole point of sniffing. A stranger uploading script.png that is not a PNG must not end
        // up with those bytes stored under an image content type and handed to the master's browser.
        assertThatThrownBy(() -> service.attach(message(), List.of(
                file("photo.png", "image/png", "<?php echo 1; ?>".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessage("error.upload.attachment-type");
    }

    @Test
    void refusesAnythingThatIsNeitherPhotoNorPdf() {
        assertThatThrownBy(() -> service.attach(message(), List.of(
                file("archive.zip", "application/zip",
                        new byte[]{0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0}))))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessage("error.upload.attachment-type");
    }

    @Test
    void refusesASixthFileAndStoresNothingAtAll() throws IOException {
        // Rejected before the loop, so the message does not end up with five of six attachments and a
        // sender who was told it failed.
        List<MultipartFile> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(file("p" + i + ".png", "image/png", PNG_HEADER));
        }

        assertThatThrownBy(() -> service.attach(message(), six))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessage("error.upload.too-many");
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesAFileOverTenMegabytes() {
        byte[] big = new byte[(int) MessageFileService.MAX_FILE_BYTES + 1];
        System.arraycopy(PNG_HEADER, 0, big, 0, PNG_HEADER.length);

        assertThatThrownBy(() -> service.attach(message(), List.of(file("huge.png", "image/png", big))))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .hasMessage("error.upload.too-large");
    }

    @Test
    void keepsTheSendersNameAsALabelWithAnyPathStrippedOut() throws IOException {
        // The name is shown to the master and used for the download filename. It is never a path, and
        // a name that looks like one must not survive as one.
        storageAccepts();

        service.attach(message(), List.of(
                file("../../../etc/passwd.png", "image/png", PNG_HEADER)));

        ArgumentCaptor<ProjectMessageFile> saved = ArgumentCaptor.forClass(ProjectMessageFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).isEqualTo("passwd.png");
    }

    @Test
    void namesAnUnnamedUploadAfterWhatItActuallyIs() throws IOException {
        storageAccepts();

        service.attach(message(), List.of(file(null, "application/pdf", PDF_HEADER)));

        ArgumentCaptor<ProjectMessageFile> saved = ArgumentCaptor.forClass(ProjectMessageFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).isEqualTo("attachment.pdf");
    }

    @Test
    void ignoresEmptyPartsRatherThanStoringZeroByteFiles() throws IOException {
        // A form submitted with the file input untouched still sends an empty part in some browsers.
        List<ProjectMessageFile> saved = service.attach(message(),
                List.of(file("", "application/octet-stream", new byte[0])));

        assertThat(saved).isEmpty();
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void openingAFileStampsWhenItWasLastOpened() throws IOException {
        // That stamp is the retention clock; without it every file would look untouched forever.
        UUID messageId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ProjectMessageFile file = ProjectMessageFile.builder()
                .id(fileId).storageKey("messages/x.pdf").contentType("application/pdf")
                .originalName("Рахунок №7.pdf").sizeBytes(12)
                .createdAt(Instant.now().minusSeconds(3600))
                .build();
        given(fileRepository.findByIdAndMessageId(fileId, messageId)).willReturn(Optional.of(file));
        given(storage.open("messages/x.pdf")).willReturn(Optional.of(new ByteArrayInputStream(PDF_HEADER)));

        var content = service.open(messageId, fileId);

        assertThat(content.contentType()).isEqualTo("application/pdf");
        assertThat(content.downloadName()).isEqualTo("Рахунок №7.pdf");
        assertThat(file.getLastOpenedAt()).as("годинник ретенції").isNotNull();
    }

    @Test
    void openingAWarnedFileCallsOffItsDeletion() throws IOException {
        // The whole deal the warning offers: look at it and it stays. Without this the master opens the
        // file the notification told them about, and it is deleted anyway a fortnight later.
        UUID messageId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ProjectMessageFile file = ProjectMessageFile.builder()
                .id(fileId).storageKey("messages/x.pdf").contentType("application/pdf")
                .originalName("Рахунок.pdf").sizeBytes(12)
                .createdAt(Instant.now().minus(200, java.time.temporal.ChronoUnit.DAYS))
                .deletionWarnedAt(Instant.now().minus(3, java.time.temporal.ChronoUnit.DAYS))
                .build();
        given(fileRepository.findByIdAndMessageId(fileId, messageId)).willReturn(Optional.of(file));
        given(storage.open("messages/x.pdf")).willReturn(Optional.of(new ByteArrayInputStream(PDF_HEADER)));

        service.open(messageId, fileId);

        assertThat(file.getDeletionWarnedAt()).as("видалення скасоване").isNull();
        assertThat(file.getLastOpenedAt()).isNotNull();
    }

    @Test
    void aFileIsOnlyReachableThroughItsOwnMessage() throws IOException {
        // Looked up by id AND message: another master's file has to be indistinguishable from one that
        // does not exist, so the lookup can never be by id alone.
        UUID otherMessage = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        given(fileRepository.findByIdAndMessageId(fileId, otherMessage)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(otherMessage, fileId))
                .hasMessageContaining("not found");
        verify(storage, never()).open(anyString());
    }

    @Test
    void deletingAMessageDeletesItsStoredBytesEvenIfOneIsAlreadyGone() throws IOException {
        // Best effort per file: a key missing from storage must not block the master from deleting.
        ProjectMessage m = message();
        m.getFiles().addAll(List.of(
                ProjectMessageFile.builder().id(UUID.randomUUID()).storageKey("messages/a.png").build(),
                ProjectMessageFile.builder().id(UUID.randomUUID()).storageKey("messages/b.pdf").build()));
        willThrowOn("messages/a.png");

        service.deleteStoredFiles(m);

        verify(storage).delete("messages/a.png");
        verify(storage).delete(eq("messages/b.pdf"));
    }

    private void willThrowOn(String key) throws IOException {
        org.mockito.BDDMockito.willThrow(new IOException("gone")).given(storage).delete(key);
    }
}
