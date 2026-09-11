package com.majstr.backend.service;

import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new {@link Unit} has to reach every place a unit is RENDERED, and the client-facing one is the
 * easy half to forget: the portal is a hand-written page, so a missing entry shows the client the
 * raw enum code («LITRE 12») instead of «л 12» — silently, with every test green. {@code PERCENT}
 * set the precedent; V126's audit found M3, T, POINT, KM, DAY and FLOOR had been missing there
 * since they were added.
 *
 * <p>This test reads the portal page as TEXT, the same trick {@code swUpdate.test.ts} uses in the
 * PWA: a mocked assertion would only prove the mock knows about the unit.</p>
 */
class UnitRenderCoverageTest {

    private static final Path PORTAL = Path.of("src/main/resources/static/portal/index.html");

    @Test
    void portalUnitLabelMapCoversEveryUnit() throws IOException {
        String html = Files.readString(PORTAL, StandardCharsets.UTF_8);
        int start = html.indexOf("const UNIT_LABEL = {");
        assertThat(start).as("UNIT_LABEL map in the portal page").isGreaterThan(-1);
        String map = html.substring(start, html.indexOf("};", start));

        for (Unit unit : Unit.values()) {
            assertThat(map)
                    .as("portal UNIT_LABEL entry for %s — without it the client sees the raw code", unit)
                    .contains(unit.name() + ":");
        }
    }

    @Test
    void litreRendersEverywhereTheServerOwns() {
        assertThat(UnitLabel.ua(Unit.LITRE)).isEqualTo("л");
    }
}
