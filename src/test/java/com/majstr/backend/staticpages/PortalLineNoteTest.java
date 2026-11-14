package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The master's comment on an estimate line (V206) on the client's page: an (i) at the right end of the
 * row that opens it in the page's own popover — never printed under the name (owner, 2026-11-14: «дуже
 * жахливо виглядає»). Read as TEXT like the other static-page guards.
 */
class PortalLineNoteTest {

    private static String page() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void theCommentIsBehindAnInfoButtonInALastColumn_notUnderTheName() throws IOException {
        String page = page();
        assertThat(page).doesNotContain("class=\"item-note\"")
                .contains("data-note=\"${escapeHtml(i.note)}\"")
                .contains("table.with-notes th:nth-child(6), table.with-notes td:nth-child(6)")
                .contains("openInfoPopover(noteTrigger, noteTrigger.getAttribute('data-note'))");
    }

    @Test
    void everyRowSpansTheExtraColumnWhenThereIsOne() throws IOException {
        String page = page();
        assertThat(page).contains("<td colspan=\"${withNotes ? 6 : 5}\">")
                .contains("<td class=\"num\">${amount(sum)}</td>${withNotes ? '<td></td>' : ''}");
    }
}
