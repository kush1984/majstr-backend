package com.majstr.backend.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blank is not an identity (review item B-21).
 *
 * <p>The fiscal code arrives on a PATCH and the DTOs took whatever was sent, so {@code ""} was
 * stored as an identity. Every reader then keyed that receipt as one and the same string, which made
 * it the twin of every other blank one — a false duplicate warning on the read path, and on the
 * money path a sign-time reconciliation of two unrelated papers that dropped one of them from the
 * reimbursable axis and deleted its cost from {@code Прибуток}.</p>
 */
class FiscalIdentityTest {

    @Test
    void nothingPrintedIsNoIdentity() {
        assertThat(FiscalIdentity.normalize(null)).isNull();
        assertThat(FiscalIdentity.normalize("")).isNull();
        assertThat(FiscalIdentity.normalize("   ")).isNull();
        assertThat(FiscalIdentity.normalize(" 4000123456 ")).isEqualTo("4000123456");
    }

    @Test
    void aBlankPaperMatchesNothingAtAll() {
        // The bug in one line: this used to be the key "|", shared by every blank-identity receipt.
        assertThat(FiscalIdentity.key("", "")).isNull();
        assertThat(FiscalIdentity.key("  ", "  ")).isNull();
        assertThat(FiscalIdentity.key(null, null)).isNull();
    }

    @Test
    void halfAnIdentityIsNoIdentity() {
        assertThat(FiscalIdentity.key("4000123456", null)).isNull();
        assertThat(FiscalIdentity.key(null, "77")).isNull();
        assertThat(FiscalIdentity.key("4000123456", "77")).isEqualTo("4000123456|77");
    }

    /** Legacy rows were stored unnormalised, so the key normalises on READ as well as on write. */
    @Test
    void theKeyNormalisesWhatIsAlreadyStored() {
        assertThat(FiscalIdentity.key(" 4000123456 ", " 77 "))
                .isEqualTo(FiscalIdentity.key("4000123456", "77"));
    }

    @Test
    void halfAnIdentityIsRefusedOnTheWayIn() {
        assertThat(FiscalIdentity.complete("4000123456", "77")).isTrue();
        assertThat(FiscalIdentity.complete(null, null)).isTrue();
        assertThat(FiscalIdentity.complete("", "")).as("both blank reads as none").isTrue();
        assertThat(FiscalIdentity.complete("4000123456", null)).isFalse();
        assertThat(FiscalIdentity.complete("4000123456", "  ")).isFalse();
    }

    /** Both request records answer the same way — the rule cannot differ across B-04's two tables. */
    @Test
    void bothReceiptRequestsReadTheirIdentityTheSameWay() {
        var object = new ProjectReceiptRequest("Епіцентр", new BigDecimal("483.50"),
                LocalDate.of(2026, 9, 8), null, "  ", "");
        assertThat(object.normalizedFiscalFn()).isNull();
        assertThat(object.normalizedFiscalId()).isNull();
        assertThat(object.isFiscalIdentityComplete()).isTrue();

        var act = new WorkActReceiptRequest("Епіцентр", new BigDecimal("483.50"),
                BigDecimal.ZERO, LocalDate.of(2026, 9, 8), " 4000123456 ", " 77 ");
        assertThat(act.normalizedFiscalFn()).isEqualTo("4000123456");
        assertThat(act.normalizedFiscalId()).isEqualTo("77");
        assertThat(act.isFiscalIdentityComplete()).isTrue();
    }
}
