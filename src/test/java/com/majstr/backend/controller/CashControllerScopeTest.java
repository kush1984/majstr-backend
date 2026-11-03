package com.majstr.backend.controller;

import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.service.CashScope;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The «Мої гроші» object filter: nothing, {@code none}, or one canonical object id. */
class CashControllerScopeTest {

    @Test
    void absentOrBlankIsEverything() {
        assertThat(CashController.scopeOf(null)).isEqualTo(CashScope.ALL);
        assertThat(CashController.scopeOf("  ")).isEqualTo(CashScope.ALL);
    }

    @Test
    void noneIsTheRowsOfNoObject() {
        assertThat(CashController.scopeOf("none")).isEqualTo(CashScope.OFF_OBJECT);
    }

    @Test
    void anIdIsThatObject() {
        UUID id = UUID.randomUUID();
        assertThat(CashController.scopeOf(id.toString())).isEqualTo(CashScope.object(id));
    }

    /** {@code UUID.fromString("1-2-3-4-5")} parses; a filter must not quietly name another row. */
    @Test
    void aShortOrMangledIdIsRefused() {
        assertThatThrownBy(() -> CashController.scopeOf("1-2-3-4-5"))
                .isInstanceOf(PaymentValidationException.class);
        assertThatThrownBy(() -> CashController.scopeOf("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
                .isInstanceOf(PaymentValidationException.class);
    }
}
