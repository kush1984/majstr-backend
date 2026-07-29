package com.majstr.backend.service;

import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectMessageFile;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The six-month sweep on message attachments.
 *
 * <p>This deletes a master's data on a timer, so the tests are about the promises made rather than the
 * mechanics: nothing is deleted without a warning first, the warning is given once, opening the file
 * cancels it, and one master gets one notification rather than one per file.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageFileRetentionServiceTest {

    @Mock ProjectMessageFileRepository fileRepository;
    @Mock com.majstr.backend.storage.StorageService storage;
    @Mock PushService pushService;

    private static final int RETENTION_DAYS = 180;
    private static final int GRACE_DAYS = 14;

    private MessageFileRetentionService service() {
        return new MessageFileRetentionService(fileRepository, storage, pushService,
                RETENTION_DAYS, GRACE_DAYS, 200);
    }

    private final User owner = User.builder().id(UUID.randomUUID()).fullName("Майстер").build();

    private ProjectMessageFile file(String name, String projectName, Instant warnedAt) {
        Project project = Project.builder()
                .id(UUID.randomUUID()).owner(owner).name(projectName).address("вул. 1")
                .status(ProjectStatus.COMPLETED).build();
        ProjectMessage message = ProjectMessage.builder()
                .id(UUID.randomUUID()).project(project).authorName("Постачальник").message("Рахунок")
                .build();
        return ProjectMessageFile.builder()
                .id(UUID.randomUUID()).message(message)
                .storageKey("messages/" + name)
                .originalName(name).contentType("application/pdf").sizeBytes(1024)
                .createdAt(Instant.now().minus(400, ChronoUnit.DAYS))
                .deletionWarnedAt(warnedAt)
                .build();
    }

    // =============================================================================================

    @Test
    void warningStampsTheFilesAndAsksForTheRightAge() {
        ProjectMessageFile f = file("Рахунок.pdf", "Квартира", null);
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of(f));

        service().warnAboutOldFiles();

        assertThat(f.getDeletionWarnedAt()).as("попередження дається один раз і фіксується").isNotNull();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(fileRepository).findDueForWarning(cutoff.capture(), any());
        // Roughly 180 days back; the exact instant moves with the clock.
        long days = ChronoUnit.DAYS.between(cutoff.getValue(), Instant.now());
        assertThat(days).isBetween((long) RETENTION_DAYS - 1, (long) RETENTION_DAYS + 1);
    }

    @Test
    void oneMasterGetsOneNotificationNamingTheObjectAndTheDeadline() {
        // A year-old job with twelve attachments must not mean twelve notifications about one thing.
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of(
                file("Рахунок.pdf", "Квартира", null),
                file("Фото.jpg", "Квартира", null),
                file("Кошторис.pdf", "Квартира", null)));

        service().warnAboutOldFiles();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushService, times(1)).sendToUser(any(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("3 файлів").contains("Квартира").contains("14 дн");
    }

    @Test
    void aSingleFileIsNamedInTheWarning() {
        given(fileRepository.findDueForWarning(any(), any()))
                .willReturn(List.of(file("Рахунок №7.pdf", "Квартира", null)));

        service().warnAboutOldFiles();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushService).sendToUser(any(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("Рахунок №7.pdf").contains("Квартира");
    }

    @Test
    void doesNotSayTaInshykhWhenEveryFileIsOnTheSameObject() {
        // Saying "«X» та інших" when they are all on X sends the master hunting for objects that do not
        // exist.
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of(
                file("a.pdf", "Квартира", null), file("b.pdf", "Квартира", null)));

        service().warnAboutOldFiles();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushService).sendToUser(any(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).doesNotContain("та інших");
    }

    @Test
    void saysTaInshykhWhenTheFilesSpanSeveralObjects() {
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of(
                file("a.pdf", "Квартира", null), file("b.pdf", "Котедж", null)));

        service().warnAboutOldFiles();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushService).sendToUser(any(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("та інших");
    }

    @Test
    void aFailedNotificationStillLeavesTheWarningInPlace() {
        // Fail-soft: the app shows the warning on the file itself, so a push that never went out must
        // not roll the stamp back and start the six months again.
        ProjectMessageFile f = file("Рахунок.pdf", "Квартира", null);
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of(f));
        willThrow(new RuntimeException("no subscription"))
                .given(pushService).sendToUser(any(), anyString(), anyString(), anyString());

        assertThatCode(() -> service().warnAboutOldFiles()).doesNotThrowAnyException();
        assertThat(f.getDeletionWarnedAt()).isNotNull();
    }

    @Test
    void warningNobodyIsDueSendsNothing() {
        given(fileRepository.findDueForWarning(any(), any())).willReturn(List.of());

        service().warnAboutOldFiles();

        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void deletionTakesTheBytesAndThenTheRows() throws IOException {
        ProjectMessageFile f = file("Рахунок.pdf", "Квартира",
                Instant.now().minus(GRACE_DAYS + 1, ChronoUnit.DAYS));
        given(fileRepository.findDueForDeletion(any(), any())).willReturn(List.of(f));

        service().deleteWarnedFiles();

        verify(storage).delete(f.getStorageKey());
        verify(fileRepository).deleteAll(List.of(f));
    }

    @Test
    void oneUndeletableKeyDoesNotPinTheWholeBatch() throws IOException {
        // The master was told these files are gone; a storage key that refuses must not keep the rest.
        ProjectMessageFile bad = file("bad.pdf", "Квартира", Instant.now().minus(20, ChronoUnit.DAYS));
        ProjectMessageFile good = file("good.pdf", "Квартира", Instant.now().minus(20, ChronoUnit.DAYS));
        given(fileRepository.findDueForDeletion(any(), any())).willReturn(List.of(bad, good));
        willThrow(new IOException("gone")).given(storage).delete(bad.getStorageKey());

        service().deleteWarnedFiles();

        verify(storage).delete(good.getStorageKey());
        verify(fileRepository).deleteAll(List.of(bad, good));
    }

    @Test
    void deletionAsksForFilesWarnedLongerAgoThanTheGracePeriod() throws IOException {
        given(fileRepository.findDueForDeletion(any(), any())).willReturn(List.of());

        service().deleteWarnedFiles();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(fileRepository).findDueForDeletion(cutoff.capture(), any());
        long days = ChronoUnit.DAYS.between(cutoff.getValue(), Instant.now());
        assertThat(days).isBetween((long) GRACE_DAYS - 1, (long) GRACE_DAYS + 1);
        verify(storage, never()).delete(anyString());
    }
}
