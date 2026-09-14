package com.majstr.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DejaVu TTFs are committed rather than downloaded at build time, so neither CI nor the Railway
 * image build depends on SourceForge answering. That trade needs a guard the download never did: a
 * committed binary can be mangled on its way through git — {@code core.autocrlf} rewriting line
 * endings inside a font is the realistic way — and a corrupt TTF only fails when a PDF renders,
 * far from the cause. The hashes below pin the exact upstream bytes.
 */
class PdfFontProviderTest {

    /** SHA-256 of the two files as published inside dejavu-fonts-ttf-2.37.zip. */
    private static final String REGULAR_SHA256 =
            "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954";
    private static final String BOLD_SHA256 =
            "e6476c1b80502924294eed40894c5b18e06c181444ca953e5334262df9c27724";

    @Test
    void shipsTheUpstreamFontsByteForByte() throws Exception {
        assertThat(sha256("fonts/DejaVuSans.ttf")).isEqualTo(REGULAR_SHA256);
        assertThat(sha256("fonts/DejaVuSans-Bold.ttf")).isEqualTo(BOLD_SHA256);
    }

    @Test
    void loadsFontsThatCanDrawUkrainian() throws Exception {
        PdfFontProvider provider = new PdfFontProvider();
        provider.init();

        // The whole reason DejaVu is here. Given as code points rather than letters because the
        // build sets no source encoding, so a Cyrillic literal would be read differently by a
        // cp1251 Windows JDK than by the UTF-8 CI runner. In order: YI, UKRAINIAN IE,
        // BYELORUSSIAN-UKRAINIAN I, GHE WITH UPTURN — the letters a Latin-only fallback loses.
        for (int glyph : new int[] {0x0457, 0x0454, 0x0456, 0x0491}) {
            assertThat(provider.regular(10f).getBaseFont().charExists(glyph))
                    .as("regular DejaVu must contain U+%04X", glyph)
                    .isTrue();
            assertThat(provider.bold(10f).getBaseFont().charExists(glyph))
                    .as("bold DejaVu must contain U+%04X", glyph)
                    .isTrue();
        }
    }

    private static String sha256(String resource) throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = new ClassPathResource(resource).getInputStream()) {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }
}
