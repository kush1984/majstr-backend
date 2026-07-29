package com.majstr.backend.service;

import com.majstr.backend.storage.UnsupportedMediaTypeException;

import java.util.Optional;

/**
 * Sniffs image bytes by their magic header — defending against a renamed
 * .exe.png and friends. Caller passes the first ~16 bytes of the upload.
 */
public final class ImageContentTypeDetector {

    public enum ImageKind {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg");

        public final String contentType;
        public final String extension;

        ImageKind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    private ImageContentTypeDetector() {}

    public static ImageKind detect(byte[] header) {
        // Exception messages are bundle keys, resolved by GlobalExceptionHandler.
        if (header == null || header.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        return tryDetect(header)
                .orElseThrow(() -> new UnsupportedMediaTypeException("error.upload.type"));
    }

    /**
     * The same sniff without the exception, for callers that accept more than images and need to try
     * another format before giving up — message attachments also take PDF.
     */
    public static Optional<ImageKind> tryDetect(byte[] header) {
        if (header == null) return Optional.empty();
        if (isPng(header)) return Optional.of(ImageKind.PNG);
        if (isJpeg(header)) return Optional.of(ImageKind.JPEG);
        return Optional.empty();
    }

    private static boolean isPng(byte[] h) {
        // 89 50 4E 47 0D 0A 1A 0A
        return h.length >= 8
                && (h[0] & 0xFF) == 0x89
                && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
                && h[4] == 0x0D && h[5] == 0x0A
                && (h[6] & 0xFF) == 0x1A && h[7] == 0x0A;
    }

    private static boolean isJpeg(byte[] h) {
        // FF D8 FF
        return h.length >= 3
                && (h[0] & 0xFF) == 0xFF
                && (h[1] & 0xFF) == 0xD8
                && (h[2] & 0xFF) == 0xFF;
    }
}
