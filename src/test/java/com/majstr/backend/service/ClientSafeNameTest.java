package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The markup rate must never reach the client in a NAME (review B-74).
 *
 * <p>New copies are no longer named «… +20%», but the ones already made are, and the name is
 * printed on the portal page and in the act's group headers. What matters here is the narrowness:
 * a position name that happens to end in a percentage of something real must survive.</p>
 */
class ClientSafeNameTest {

    @Test
    void stripsATrailingRate() {
        assertThat(ClientSafeName.of("Санвузол +20%")).isEqualTo("Санвузол");
        assertThat(ClientSafeName.of("Санвузол -15%")).isEqualTo("Санвузол");
        assertThat(ClientSafeName.of("Санвузол −10,5 %")).isEqualTo("Санвузол"); // real minus sign
        assertThat(ClientSafeName.of("Кошторис (+5%)")).isEqualTo("Кошторис");
        // B-98: not only at the very end, and not only once.
        assertThat(ClientSafeName.of("Санвузол +20% (копія)")).isEqualTo("Санвузол (копія)");
        assertThat(ClientSafeName.of("Санвузол +20% +5%")).isEqualTo("Санвузол");
    }

    @Test
    void keepsANameThatMerelyMentionsAPercentage() {
        assertThat(ClientSafeName.of("Фарбування 2 % розчином"))
                .isEqualTo("Фарбування 2 % розчином");
        assertThat(ClientSafeName.of("Знижка 10 % від кошторису"))
                .isEqualTo("Знижка 10 % від кошторису");
    }

    /** A name that is NOTHING but a rate stays as it is: an empty heading is worse than a hint. */
    @Test
    void neverStripsANameToNothing() {
        assertThat(ClientSafeName.of("+20%")).isEqualTo("+20%");
    }

    @Test
    void passesNullAndBlankThrough() {
        assertThat(ClientSafeName.of(null)).isNull();
        assertThat(ClientSafeName.of("  ")).isEqualTo("  ");
    }
}
