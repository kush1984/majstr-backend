package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The portal's signing flow before the meetup (review P0-1..3), read as TEXT like the other
 * static-page guards — nothing server-side renders this script.
 */
class PortalSignFlowTest {

    private static String page() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
    }

    @Test
    void aSignatureRendersItsOwnAnswerAndSaysThankYou() throws IOException {
        String page = page();
        // The POST's answer is the signed page; a second GET could fail after the signature landed.
        assertThat(page).contains("render(portalToken ? data : adaptLegacy(data));")
                .contains("showSigned('Кошторис підписано')")
                .contains("showSigned('Приймання робіт підтверджено')")
                .contains("Майстер уже отримав сповіщення.");
    }

    @Test
    void aDoubleTapSendsOneSignature_andAnAlreadySignedAnswerIsSuccess() throws IOException {
        String page = page();
        assertThat(page).contains("if (submit.disabled) return;")
                .contains("submit.textContent = 'Підписуємо…';")
                .contains("await errorCode(resp) === 'ESTIMATE_SIGNED'")
                .contains("await errorCode(resp) === 'WORK_ACT_SIGNED'");
    }

    @Test
    void a429OnLoadWaitsAndRetriesByItself() throws IOException {
        assertThat(page()).contains("if (resp.status === 429) {")
                .contains("waitAndRetry(retryAfter(resp));")
                .contains("Забагато людей одночасно — пробуємо ще раз за");
    }
}
