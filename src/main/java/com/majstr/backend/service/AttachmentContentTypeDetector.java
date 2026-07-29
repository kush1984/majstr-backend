package com.majstr.backend.service;

import com.majstr.backend.storage.UnsupportedMediaTypeException;

/**
 * Sniffs a message attachment by its magic header: photo or PDF, and nothing else.
 *
 * <p>Sniffed rather than trusted, because the uploader is a stranger. The public form takes files from
 * whoever holds the link, so the declared filename and Content-Type are both hostile input — an
 * {@code invoice.pdf} that is really an executable must not be stored as a PDF and handed back to the
 * master's browser as one.</p>
 *
 * <p>Photo detection is delegated, not copied: one place owns the PNG and JPEG signatures.</p>
 */
public final class AttachmentContentTypeDetector {

    public enum AttachmentKind {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        PDF("application/pdf", "pdf");

        public final String contentType;
        public final String extension;

        AttachmentKind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public boolean isImage() {
            return this != PDF;
        }
    }

    /** "%PDF-" — the signature every PDF starts with. */
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

    private AttachmentContentTypeDetector() {}

    public static AttachmentKind detect(byte[] header) {
        // Exception messages are bundle keys, resolved by GlobalExceptionHandler.
        if (header == null || header.length < PDF_MAGIC.length) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (isPdf(header)) {
            return AttachmentKind.PDF;
        }
        return ImageContentTypeDetector.tryDetect(header)
                .map(kind -> switch (kind) {
                    case PNG -> AttachmentKind.PNG;
                    case JPEG -> AttachmentKind.JPEG;
                })
                .orElseThrow(() -> new UnsupportedMediaTypeException("error.upload.attachment-type"));
    }

    private static boolean isPdf(byte[] h) {
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (h[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
