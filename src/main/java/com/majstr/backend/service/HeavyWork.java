package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.exception.ServerBusyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * How much memory-hungry work may run at once (review B-122). Tomcat allows 200 threads and the heap
 * is 75 % of a small container, so nothing else stops a burst of PDFs or drawings from exhausting it.
 *
 * <p>Two gates, because the two kinds of work last very differently. A PDF takes a moment, so it waits
 * a little for its turn. An import can sit on a model call for minutes, so it never waits, and it never
 * shares permits with a PDF. A signature does not come through here at all: the client's tap may not
 * fail because somebody else is downloading.</p>
 */
@Component
public class HeavyWork {

    private static final long PDF_RETRY_AFTER_SECONDS = 10;
    private static final long IMPORT_RETRY_AFTER_SECONDS = 30;

    private final Semaphore pdfs;
    private final Semaphore imports;
    private final long pdfWaitSeconds;

    public HeavyWork(@Value("${app.heavy-work.pdf-permits:4}") int pdfPermits,
                     @Value("${app.heavy-work.import-permits:3}") int importPermits,
                     @Value("${app.heavy-work.pdf-wait-seconds:10}") long pdfWaitSeconds) {
        this.pdfs = new Semaphore(pdfPermits, true);
        this.imports = new Semaphore(importPermits);
        this.pdfWaitSeconds = pdfWaitSeconds;
    }

    @FunctionalInterface
    public interface PdfWork {
        byte[] run() throws IOException, DocumentException;
    }

    public byte[] pdf(PdfWork work) throws IOException, DocumentException {
        acquirePdf();
        try {
            return work.run();
        } finally {
            pdfs.release();
        }
    }

    private void acquirePdf() {
        try {
            if (pdfs.tryAcquire(pdfWaitSeconds, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new ServerBusyException(PDF_RETRY_AFTER_SECONDS);
    }

    /** Pair with {@link #leaveImport()}. */
    public void enterImport() {
        if (!imports.tryAcquire()) {
            throw new ServerBusyException(IMPORT_RETRY_AFTER_SECONDS);
        }
    }

    public void leaveImport() {
        imports.release();
    }
}
