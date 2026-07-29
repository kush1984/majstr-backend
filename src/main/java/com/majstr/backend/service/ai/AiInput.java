package com.majstr.backend.service.ai;

import java.util.List;

/**
 * What we send a vision model, described in our own terms rather than a vendor's.
 *
 * <p>This exists so a second provider is a possibility at all. The call sites used to build
 * Anthropic's wire shape directly ({@code {"type":"image","source":{"type":"base64",…}}}), which
 * meant every recognition flow was hard-wired to one vendor's JSON — swapping providers would have
 * meant editing four services rather than adding one class. Here a sheet is "a PDF" or "an image
 * plus an instruction", and each {@link JsonExtractor} renders that into whatever its API wants.</p>
 */
public sealed interface AiInput {

    /** An instruction or a pre-rendered text grid. */
    record Text(String text) implements AiInput {}

    /** A photo or a rasterised drawing. {@code mediaType} is e.g. {@code image/png}. */
    record Image(String mediaType, byte[] bytes) implements AiInput {}

    /**
     * A PDF handed over whole — both providers render the pages themselves, which is why the deploy
     * needs no poppler. {@code filename} is a label some APIs require alongside the bytes.
     */
    record Pdf(String filename, byte[] bytes) implements AiInput {}

    static List<AiInput> pdf(byte[] bytes, String instruction) {
        return List.of(new Pdf("document.pdf", bytes), new Text(instruction));
    }

    static List<AiInput> image(String mediaType, byte[] bytes, String instruction) {
        return List.of(new Image(mediaType, bytes), new Text(instruction));
    }

    static List<AiInput> text(String text) {
        return List.of(new Text(text));
    }
}
