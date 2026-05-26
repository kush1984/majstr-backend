package com.majstr.backend.service;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads DejaVu Sans (regular + bold) into OpenPDF so the renderer can
 * draw Cyrillic. The built-in 14 PDF fonts are Latin-only; without a
 * Unicode TTF, Ukrainian glyphs render as question marks.
 *
 * <p>Font files are downloaded by the {@code downloadPdfFonts} Gradle task
 * into {@code src/main/resources/fonts/} on first build.</p>
 */
@Slf4j
@Component
public class PdfFontProvider {

    private static final String REGULAR_PATH = "fonts/DejaVuSans.ttf";
    private static final String BOLD_PATH = "fonts/DejaVuSans-Bold.ttf";

    private BaseFont regular;
    private BaseFont bold;

    @PostConstruct
    void init() throws IOException {
        regular = loadEmbedded("DejaVuSans.ttf", REGULAR_PATH);
        bold = loadEmbedded("DejaVuSans-Bold.ttf", BOLD_PATH);
        log.info("Loaded DejaVu Sans fonts for PDF rendering");
    }

    public Font regular(float size) {
        return new Font(regular, size);
    }

    public Font bold(float size) {
        return new Font(bold, size);
    }

    private static BaseFont loadEmbedded(String name, String resource) throws IOException {
        try (InputStream stream = new ClassPathResource(resource).getInputStream()) {
            byte[] bytes = stream.readAllBytes();
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }
}
