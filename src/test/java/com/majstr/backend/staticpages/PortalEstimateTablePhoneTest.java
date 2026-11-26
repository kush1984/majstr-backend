package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The estimate table on a phone (an owner's screenshot, 2026-11-26): names broke at a random letter
 * («звукоізол/яція») because the name cell allowed `overflow-wrap: anywhere` in a column a quarter wide,
 * and a larger system font piled «К-СТЬ» onto «ЦІНА, ₴». Read as TEXT like the other static-page guards.
 */
class PortalEstimateTablePhoneTest {

    private static String phoneBlock() throws IOException {
        // The checkout may carry CRLF; every anchor below is written with \n.
        String page = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int start = page.indexOf("@media (max-width: 480px) {\n            .container {");
        assertThat(start).isPositive();
        return page.substring(start, page.indexOf("}\n        }", start));
    }

    @Test
    void aNameBreaksBetweenSyllables_notAtARandomLetter() throws IOException {
        assertThat(phoneBlock()).contains("td:first-child { hyphens: auto; overflow-wrap: break-word; }");
    }

    /** The preview banner was 100 px tall over the page the master was checking: the loading line's
     *  id-level rule ({@code #root > p}, 40 px padding) out-weighed the banner's own. */
    @Test
    void thePreviewBannerIsOneShortLine_notTheLoadingLinesPadding() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
        assertThat(page).contains("#root > p:not(.preview-banner) {")
                .doesNotContain("#root > p {")
                .contains("<p class=\"preview-banner\" role=\"status\">👁 Попередній перегляд — так побачить клієнт</p>");
    }

    @Test
    void theHeaderHasNoLetterSpacing_andTheFiguresKeepTheirRoom() throws IOException {
        String phone = phoneBlock();
        assertThat(phone).contains("thead th { letter-spacing: 0; }")
                // «15 925,00» in the price, «135 000,00» in the sum — measured on a 360 px screen.
                .contains("th:nth-child(4), td:nth-child(4) { width: 25%; }")
                .contains("th:nth-child(5), td:nth-child(5) { width: 27%; }");
    }
}
