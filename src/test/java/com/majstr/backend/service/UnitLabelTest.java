package com.majstr.backend.service;

import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitLabelTest {

    @Test
    void linearMeterHasUkrainianLabel() {
        assertThat(UnitLabel.ua(Unit.LINEAR_METER)).isEqualTo("м.п.");
    }

    @Test
    void everyUnitHasAnExplicitLabel() {
        for (Unit u : Unit.values()) {
            assertThat(UnitLabel.ua(u))
                    .as("label for %s", u)
                    .isNotBlank()
                    .isNotEqualTo(u.name());
        }
    }
}
