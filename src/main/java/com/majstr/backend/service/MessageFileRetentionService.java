package com.majstr.backend.service;

import com.majstr.backend.entity.ProjectMessageFile;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import com.majstr.backend.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Six-month retention on message attachments: warn, wait, then delete.
 *
 * <p>Storage a master is paying for is full of files nobody has looked at since the job ended. Deleting
 * them silently would be data loss dressed up as housekeeping, so there are two passes. The first tells
 * the master which object's file is going and when. The second removes what was ignored anyway.</p>
 *
 * <p>Opening a warned file clears the warning, and that is the whole offer: look at it and it stays. The
 * master needs to do nothing else, and there is no setting to find.</p>
 *
 * <p>Both passes are batched. An unbounded sweep on a table that has been accumulating for a year would
 * hold one transaction over thousands of rows and send a master a notification per file; a nightly
 * batch drains the same backlog over a few nights and stays predictable.</p>
 *
 * <p>Single-node, like {@link TokenCleanupService} — if this ever runs on several instances, guard it
 * with a shared lock.</p>
 */
@Slf4j
@Service
public class MessageFileRetentionService {
    // No @RequiredArgsConstructor: the constructor is written out below because the schedule values come
    // from @Value, and a generated one alongside it left Spring with two to choose from.

    /** How long a file may go untouched before the master is warned. */
    private final int retentionDays;
    /** How long the warning stands before the file goes. */
    private final int graceDays;
    /** Files handled per pass, per night. */
    private final int batchSize;

    private final ProjectMessageFileRepository fileRepository;
    private final StorageService storage;
    private final PushService pushService;

    public MessageFileRetentionService(
            ProjectMessageFileRepository fileRepository,
            StorageService storage,
            PushService pushService,
            @Value("${app.message-files.retention-days:180}") int retentionDays,
            @Value("${app.message-files.grace-days:14}") int graceDays,
            @Value("${app.message-files.batch-size:200}") int batchSize) {
        this.fileRepository = fileRepository;
        this.storage = storage;
        this.pushService = pushService;
        this.retentionDays = retentionDays;
        this.graceDays = graceDays;
        this.batchSize = batchSize;
    }

    /**
     * Pass one: mark the old files and tell their owners.
     *
     * <p>One notification per master per night, listing how many files and naming one object, rather
     * than a burst of them — a master whose year-old job had twelve attachments would otherwise get
     * twelve notifications about the same thing.</p>
     */
    @Scheduled(cron = "${app.message-files.warn-cron:0 30 3 * * *}")
    @Transactional
    public void warnAboutOldFiles() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<ProjectMessageFile> due =
                fileRepository.findDueForWarning(cutoff, PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return;
        }

        Instant warnedAt = Instant.now();
        // Grouped by owner, keeping insertion order so the object named in the notification is the one
        // with the oldest file — the most likely to be forgotten.
        Map<UUID, List<ProjectMessageFile>> byOwner = new LinkedHashMap<>();
        for (ProjectMessageFile file : due) {
            file.setDeletionWarnedAt(warnedAt);
            byOwner.computeIfAbsent(ownerOf(file).getId(), k -> new ArrayList<>()).add(file);
        }

        for (List<ProjectMessageFile> files : byOwner.values()) {
            notifyOwner(files);
        }
        log.info("Message-file retention warned about {} files across {} masters",
                due.size(), byOwner.size());
    }

    /**
     * Pass two: delete what the notice ran out on.
     *
     * <p>The bytes go first. A row deleted before its object leaves a key nothing will ever come back
     * for; bytes deleted before the row leave a row whose file is already gone, which the download
     * endpoint answers as a 404 — recoverable, and visible.</p>
     */
    @Scheduled(cron = "${app.message-files.delete-cron:0 45 3 * * *}")
    @Transactional
    public void deleteWarnedFiles() {
        Instant cutoff = Instant.now().minus(graceDays, ChronoUnit.DAYS);
        List<ProjectMessageFile> due =
                fileRepository.findDueForDeletion(cutoff, PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return;
        }
        for (ProjectMessageFile file : due) {
            try {
                storage.delete(file.getStorageKey());
            } catch (IOException | RuntimeException e) {
                // Keep going and take the row anyway: a key that cannot be deleted must not pin the
                // whole batch, and the master was told this file is gone.
                log.warn("Retention could not delete stored attachment {}: {}",
                        file.getStorageKey(), e.getMessage());
            }
        }
        fileRepository.deleteAll(due);
        log.info("Message-file retention deleted {} files", due.size());
    }

    private void notifyOwner(List<ProjectMessageFile> files) {
        User owner = ownerOf(files.get(0));
        String projectName = files.get(0).getMessage().getProject().getName();
        // Whether every file is on the same object decides the wording: saying "«X» та інші" when they
        // are all on X sends the master looking for objects that do not exist.
        boolean oneObject = files.stream()
                .allMatch(f -> f.getMessage().getProject().getName().equals(projectName));
        String where = oneObject
                ? "обʼєкта «" + projectName + "»"
                : "обʼєкта «" + projectName + "» та інших";
        String body = files.size() == 1
                ? "Файл «" + displayName(files.get(0)) + "» у повідомленні до " + where
                        + " буде видалено через " + graceDays + " дн. Відкрийте його, щоб зберегти."
                : files.size() + " файлів у повідомленнях до " + where + " буде видалено через "
                        + graceDays + " дн. Відкрийте потрібні, щоб зберегти.";
        try {
            pushService.sendToUser(owner, "Файли будуть видалені", body,
                    "/projects/" + files.get(0).getMessage().getProject().getId());
        } catch (RuntimeException e) {
            // Fail-soft: a notification that does not go out must not roll back the warning. The file
            // also carries the warning in the app itself, so the master still sees it on the object.
            log.warn("Could not warn master {} about {} expiring files: {}",
                    owner.getId(), files.size(), e.getMessage());
        }
    }

    private static User ownerOf(ProjectMessageFile file) {
        return file.getMessage().getProject().getOwner();
    }

    private static String displayName(ProjectMessageFile file) {
        String name = file.getOriginalName();
        return name == null || name.isBlank() ? "вкладення" : name;
    }
}
