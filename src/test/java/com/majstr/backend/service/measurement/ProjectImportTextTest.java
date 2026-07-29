package com.majstr.backend.service.measurement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text cleanup on the extracted-text path — pure functions, no Spring and no mocks.
 *
 * <p>Separate from {@code ProjectImportServiceTest} on purpose: that class wires a service with
 * mocks, and a pure-function test living there trips Mockito's strict-stub check for not using
 * them.</p>
 */
class ProjectImportTextTest {

    @Test
    void textPaintedTwiceOverItselfIsCollapsedBeforeTheModelSeesIt() {
        // A real studio's PDFs draw every string on top of itself: invisible on paper, and on the
        // text path it doubles every quantity in a specification the master then buys from.
        assertThat(ProjectImportService.dedupeDoubledText("02_обмірний план 02_обмірний план"))
                .isEqualTo("02_обмірний план");
        assertThat(ProjectImportService.dedupeDoubledText("ТВ ТВ духовка духовка холод холод"))
                .isEqualTo("ТВ духовка холод");
        // Line by line, and a line that is not doubled must come through untouched.
        assertThat(ProjectImportService.dedupeDoubledText("Плитка Плитка\n94,5 м²"))
                .isEqualTo("Плитка\n94,5 м²");
        assertThat(ProjectImportService.dedupeDoubledText("Плінтус дубовий 12,5 м"))
                .isEqualTo("Плінтус дубовий 12,5 м");
        assertThat(ProjectImportService.dedupeDoubledText(null)).isNull();
        assertThat(ProjectImportService.dedupeDoubledText("")).isEmpty();
    }
}
