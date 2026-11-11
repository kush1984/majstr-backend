package com.majstr.backend.email;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A typed name reaches an inbox from our own domain, so it must arrive as text, never as markup. */
class ResendEmailHtmlTest {

    private static final String HOSTILE = "<script>x</script>\"><a href=https://evil.example>тисни</a>";
    private static final String LINK = "https://majstr.pro/verify-email?token=abc";

    private static List<String> everyTemplate(String value) {
        return List.of(
                ResendEmailService.verificationHtml(value, LINK, true),
                ResendEmailService.passwordResetHtml(value, LINK),
                ResendEmailService.estimateShareHtml(value, value, value, LINK),
                ResendEmailService.signedActHtml(value, value, value));
    }

    @Test
    void aHostileValueArrivesAsTextInEveryTemplate() {
        for (String html : everyTemplate(HOSTILE)) {
            assertThat(html)
                    .doesNotContain("<script")
                    .doesNotContain("<a href=https://evil.example")
                    .contains("&lt;script&gt;");
        }
    }

    @Test
    void ordinaryUkrainianTextIsLeftReadable() {
        for (String html : everyTemplate("Олена Іваненко")) {
            assertThat(html).contains("Олена Іваненко");
        }
    }

    @Test
    void theOwnLinkStillWorks() {
        assertThat(ResendEmailService.verificationHtml("Олена", LINK, true)).contains("href=\"" + LINK + "\"");
    }

    @Test
    void aMissingNameLeavesNoStrayComma() {
        assertThat(ResendEmailService.verificationHtml(null, LINK, true)).contains("Вітаємо в Majstr!");
        assertThat(ResendEmailService.verificationHtml(" ", LINK, true)).contains("Вітаємо в Majstr!");
    }

    @Test
    void theLetterPromisesWhatVerificationStillGates() {
        // Sharing and the PDF no longer need a verified email (share-without-verification): the
        // letter names the account, the PRO trial and the letter to the client — not «щоб надсилати».
        String html = ResendEmailService.verificationHtml("Олена", LINK, true).replaceAll("\\s+", " ");
        assertThat(html).contains("пробний PRO").contains("кошторис клієнту листом")
                .doesNotContain("щоб надсилати кошториси клієнтам");
    }

    @Test
    void theTrialIsNamedOnlyWhileItIsOffered() {
        // trial-days 0 switches the trial off (2026-11-11): the letter must not promise it.
        String off = ResendEmailService.verificationHtml("Олена", LINK, false).replaceAll("\\s+", " ");
        assertThat(off).doesNotContain("пробн")
                .contains("не втратите доступ до акаунта і зможете надсилати кошторис клієнту листом");
        String on = ResendEmailService.verificationHtml("Олена", LINK, true).replaceAll("\\s+", " ");
        assertThat(on).contains("не втратите доступ до акаунта, зможете взяти пробний PRO і надсилати");
    }
}
