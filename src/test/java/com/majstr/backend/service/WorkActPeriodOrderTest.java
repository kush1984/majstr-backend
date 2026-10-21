package com.majstr.backend.service;

import com.majstr.backend.exception.WorkActValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Роботи виконано в період: з 20.10 по 19.10» reached two signed acts, their PDF and the client's
 * portal — nothing checked the order on either door (create, header update).
 */
class WorkActPeriodOrderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    @Test
    void aPeriodThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> WorkActService.requirePeriodInOrder(DAY.plusDays(1), DAY))
                .isInstanceOf(WorkActValidationException.class);
    }

    @Test
    void aOneDayPeriodAndAnOpenEndAreFine() {
        assertThatCode(() -> WorkActService.requirePeriodInOrder(DAY, DAY)).doesNotThrowAnyException();
        assertThatCode(() -> WorkActService.requirePeriodInOrder(DAY, DAY.plusDays(3))).doesNotThrowAnyException();
        assertThatCode(() -> WorkActService.requirePeriodInOrder(null, DAY)).doesNotThrowAnyException();
    }
}
