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
                ResendEmailService.verificationHtml(value, LINK),
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
        assertThat(ResendEmailService.verificationHtml("Олена", LINK)).contains("href=\"" + LINK + "\"");
    }

    @Test
    void aMissingNameLeavesNoStrayComma() {
        assertThat(ResendEmailService.verificationHtml(null, LINK)).contains("Вітаємо в Majstr!");
        assertThat(ResendEmailService.verificationHtml(" ", LINK)).contains("Вітаємо в Majstr!");
    }

    @Test
    void theLetterPromisesWhatVerificationStillGates() {
        // Sharing and the PDF no longer need a verified email (share-without-verification): the
        // letter names the account, the PRO trial and the letter to the client — not «щоб надсилати».
        String html = ResendEmailService.verificationHtml("Олена", LINK).replaceAll("\\s+", " ");
        assertThat(html).contains("пробний PRO").contains("кошторис клієнту листом")
                .doesNotContain("щоб надсилати кошториси клієнтам");
    }
}
