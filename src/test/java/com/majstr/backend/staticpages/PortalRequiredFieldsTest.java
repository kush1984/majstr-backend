package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reported from prod 2026-10-15: the client typed his name, pressed «Підписати» in the dialog and
 * nothing happened — no request, no message, the dialog stayed open. The phone was empty, and the
 * handler's answer to an empty required field was a bare {@code return}. The fields sit outside a
 * {@code <form>}, so the browser's own {@code required} prompt never shows either.
 *
 * <p>The owner's ruling: the phone is OPTIONAL, and every required field carries a star; pressed
 * empty, it gets a red frame, a red star and the reason under it. Read as TEXT, like
 * {@link PortalTouchTargetsTest}: nothing server-side renders these scripts, and the failure is
 * invisible — the page looks fine and the button simply does nothing.</p>
 */
class PortalRequiredFieldsTest {

    private static String page(String name) throws IOException {
        String raw = Files.readString(Path.of("src/main/resources/static", name, "index.html"),
                StandardCharsets.UTF_8);
        return raw.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", " ");
    }

    @Test
    void anEmptyRequiredFieldIsNeverASilentReturn() throws IOException {
        assertThat(Pattern.compile("if \\(!(clientName|clientPhone|message)\\b[^)]*\\) return;")
                .matcher(page("portal")).find())
                .as("a bare `if (!field) return;` swallows the press without a word")
                .isFalse();
    }

    @Test
    void everyRequiredFieldIsStarredAndNamedWhenMissing() throws IOException {
        String portal = page("portal");
        for (String id : new String[] {"signName", "actSignName", "qMessage"}) {
            assertThat(portal).as(id).containsPattern("\\['" + id + "', '[^']")
                    .containsPattern("<label for=\"" + id + "\"[^>]*>[^<]*<span class=\"req\"");
        }
        assertThat(portal).contains("label.invalid .req").contains(".field-error {");

        String message = page("message");
        for (String id : new String[] {"name", "message"}) {
            assertThat(message).as(id).containsPattern("fieldError\\('" + id + "', ")
                    .containsPattern("<label for=\"" + id + "\">[^<]*<span class=\"req\"");
        }
    }

    @Test
    void thePhoneIsOptionalAndSaysSo() throws IOException {
        String portal = page("portal");
        for (String id : new String[] {"signPhone", "actSignPhone"}) {
            assertThat(portal).as(id)
                    .containsPattern("<label for=\"" + id + "\"[^>]*>Телефон \\(необов'язково\\)</label>")
                    .doesNotContainPattern("<input id=\"" + id + "\"[^>]*required")
                    .doesNotContain("['" + id + "'");
        }
    }
}
