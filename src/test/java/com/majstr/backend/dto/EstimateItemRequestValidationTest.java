package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inline editing (2026-10-26): an emptied cell is 0 and is saved, so an ORDINARY line may carry a
 * quantity of 0 and a price of 0. A negative is still refused, and a «%» line keeps its own rules.
 */
class EstimateItemRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static EstimateItemRequest line(Unit unit, String quantity, String price, PercentBaseKind kind) {
        return new EstimateItemRequest(ItemType.WORK, "Стяжка", null, unit, new BigDecimal(quantity),
                new BigDecimal(price), null, null, true, kind, null);
    }

    private boolean valid(EstimateItemRequest req) {
        return validator.validate(req).isEmpty();
    }

    @Test
    void anOrdinaryLineMayBeZeroInQuantityAndPrice() {
        assertThat(valid(line(Unit.M2, "0", "650", null))).isTrue();
        assertThat(valid(line(Unit.M2, "12.5", "0", null))).isTrue();
        assertThat(valid(line(Unit.M2, "0", "0", null))).isTrue();
    }

    @Test
    void aNegativeQuantityOrPriceIsStillRefused() {
        assertThat(valid(line(Unit.M2, "-1", "650", null))).isFalse();
        assertThat(valid(line(Unit.M2, "1", "-1", null))).isFalse();
    }

    @Test
    void aPercentLineKeepsItsRules() {
        assertThat(valid(line(Unit.PERCENT, "0", "0", PercentBaseKind.POSITION))).isFalse();
        assertThat(valid(line(Unit.PERCENT, "-15", "0", PercentBaseKind.POSITION))).isFalse();
        assertThat(valid(line(Unit.PERCENT, "-15", "0", PercentBaseKind.TOTAL))).isTrue();
        assertThat(valid(line(Unit.PERCENT, "0", "0", PercentBaseKind.TOTAL))).isFalse();
        // A manual-base «%» line's price IS its base: it still has to be above zero.
        assertThat(valid(line(Unit.PERCENT, "10", "0", PercentBaseKind.MANUAL))).isFalse();
        assertThat(valid(line(Unit.PERCENT, "10", "1000", PercentBaseKind.MANUAL))).isTrue();
    }
}
