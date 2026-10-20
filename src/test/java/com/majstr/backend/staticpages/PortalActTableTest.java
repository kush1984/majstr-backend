package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The act's lines table on a phone. It has SIX columns with a «№» first, and the bare nth-child
 * widths in the stylesheet are the ESTIMATE table's, where «Назва» is column 1 — applied to the act
 * they gave the name the unit's 10 %, and the headers piled on top of each other (a master's
 * screenshot). Read as TEXT like the other static-page guards.
 */
class PortalActTableTest {

    private static String page() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void theActTableCarriesItsOwnColumnWidths() throws IOException {
        String page = page();
        assertThat(page).contains("<table class=\"act-lines\"")
                .contains("table.act-lines th:nth-child(2), table.act-lines td:nth-child(2) {")
                .contains("table.act-lines th:nth-child(6), table.act-lines td:nth-child(6)");
    }

    @Test
    void itsPhoneWidthsComeAfterTheBaseOnesSoTheyWin() throws IOException {
        String page = page();
        int base = page.indexOf("table.act-lines th:nth-child(6), table.act-lines td:nth-child(6) { width: 20%; }");
        int phone = page.indexOf("table.act-lines th:nth-child(6), table.act-lines td:nth-child(6) { width: 21%; }");
        assertThat(base).isPositive();
        assertThat(phone).isGreaterThan(base);
    }
}
