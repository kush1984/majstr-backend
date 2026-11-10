package com.majstr.backend.service;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.entity.PdfRender;
import com.majstr.backend.entity.Project;
import com.majstr.backend.repository.PdfRenderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes down that a PDF left the server (V196). Called once the bytes exist, outside the render's
 * transaction, and on its own pool: the download never waits for the insert and never fails with it —
 * a lost row is a slightly low number on the admin page, a lost PDF is a master who cannot send.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfRenderLog {

    /**
     * Whose document it was, read inside the render's transaction and carried out of it. A training
     * sample's PDF (V197) is not written down at all: its row would outlive the sample with a NULL
     * object (ON DELETE SET NULL) and count as a real master's PDF.
     */
    public record Origin(UUID ownerId, UUID projectId, boolean sample) {
        public Origin(UUID ownerId, UUID projectId) {
            this(ownerId, projectId, false);
        }

        static Origin of(Project project) {
            return new Origin(project.getOwner().getId(), project.getId(), project.isSample());
        }
    }

    /** What the render's transaction hands out: the job to print and whose it is. */
    public record Traced<T>(T job, Origin origin) {}

    private final PdfRenderRepository repository;

    @Async("metricsExecutor")
    public void record(Origin origin, PdfRender.Document document, PdfRender.Reader reader) {
        if (origin.sample()) {
            return;
        }
        try {
            repository.save(PdfRender.builder()
                    .ownerId(origin.ownerId())
                    .projectId(origin.projectId())
                    .document(document)
                    .reader(reader)
                    .build());
        } catch (RuntimeException e) {
            log.warn("PDF render not recorded: {}", e.getMessage());
            BackgroundErrors.capture("pdf-render-log", e);
        }
    }
}
