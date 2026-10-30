package com.majstr.backend.service.broadcast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastLetterTest {

    private static final String UNSUB = "https://api.majstr.app/api/public/unsubscribe/tok";

    @Test
    void theNameIsSubstitutedInSubjectAndBody() {
        BroadcastLetter.Rendered r = render("{{імʼя}}, питання", "Вітаю, {{імʼя}}!", "Петро");
        assertThat(r.subject()).isEqualTo("Петро, питання");
        assertThat(r.html()).contains("Вітаю, Петро!");
        assertThat(r.text()).startsWith("Вітаю, Петро!");
    }

    @Test
    void everyApostropheAKeyboardTypesIsAccepted() {
        for (String token : new String[]{"{{імʼя}}", "{{ім'я}}", "{{ім’я}}", "{{імя}}", "{{ name }}", "{{ІМʼЯ}}"}) {
            assertThat(BroadcastLetter.personalize("Вітаю, " + token + "!", "Ольга")).isEqualTo("Вітаю, Ольга!");
        }
    }

    @Test
    void withNoNameTheGreetingLosesItsComma() {
        assertThat(BroadcastLetter.personalize("Вітаю, {{імʼя}}!", "")).isEqualTo("Вітаю!");
        assertThat(render("Тема", "Вітаю, {{імʼя}}!", "   ").html()).contains("Вітаю!");
        assertThat(render("Тема", "Вітаю, {{імʼя}}!", null).html()).contains("Вітаю!");
    }

    @Test
    void theAdminsTextAndTheNameAreEscaped_theFormattingIsOurs() {
        BroadcastLetter.Rendered r = render("Т", "Привіт, {{імʼя}} <script>x</script>\n\n**жирне** і звичайне", "<b>Ім'я</b>");
        assertThat(r.html()).doesNotContain("<script>").doesNotContain("<b>Ім")
                .contains("&lt;script&gt;")
                .contains("<b>жирне</b> і звичайне");
        assertThat(r.text()).contains("жирне і звичайне").doesNotContain("**");
    }

    @Test
    void blankLinesMakeParagraphs_singleNewlinesBreakLines() {
        String html = render("Т", "Перший\nрядок\r\n\r\nДругий", "П").html();
        assertThat(html).contains("<p>Перший<br>\nрядок</p>").contains("<p>Другий</p>");
    }

    @Test
    void theButtonAndTheUnsubscribeFooterAreAlwaysThere() {
        BroadcastLetter.Rendered r = render("Т", "Текст", "П");
        assertThat(r.html()).contains("href=\"https://app.majstr.test/\"").contains("Відкрити Майстр")
                .contains("href=\"" + UNSUB + "\"").contains("Не надсилати мені такі листи");
        assertThat(r.text()).contains("Відкрити Майстр: https://app.majstr.test/").contains(UNSUB);
    }

    private static BroadcastLetter.Rendered render(String subject, String body, String name) {
        return BroadcastLetter.render(subject, body, name, "Відкрити Майстр", "https://app.majstr.test/", UNSUB);
    }
}
