package com.majstr.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client-facing pages are hand-written HTML opened on a phone, and «Підписати» is the single
 * action the portal exists for. Reported from the field 2026-09-16: the button took five taps.
 *
 * <p>The cause is not in any handler. A press that lands on the TEXT inside a control starts a
 * native text selection (and on a longer hold the callout menu), after which the browser never
 * dispatches the {@code click} — so a slow or gloved press does nothing at all. Double-tap-to-zoom
 * then makes the immediate retry read as a zoom rather than a second click. {@code user-select:
 * none} plus {@code touch-action: manipulation} on every control is the fix.</p>
 *
 * <p>Asserted against the page as TEXT, the same trick {@link UnitRenderCoverageTest} uses: there
 * is nothing to render server-side that would prove a stylesheet rule is still there, and the
 * failure mode of losing it is invisible — the page looks perfect and the button simply misses
 * taps. The PWA carries the same block in {@code src/styles/index.css}, pinned by its own
 * {@code src/styles/touch.test.ts}; change one, change the other.</p>
 */
class PortalTouchTargetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String page(String name) throws IOException {
        return Files.readString(STATIC.resolve(name).resolve("index.html"), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "message", "admin"})
    void everyClientPageOptsControlsOutOfTheDoubleTapWait(String name) throws IOException {
        assertThat(page(name))
                .as("touch-action on the controls of the %s page", name)
                .contains("touch-action: manipulation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "message", "admin"})
    void everyClientPageStopsAPressFromSelectingTextInsteadOfClicking(String name) throws IOException {
        assertThat(page(name))
                .as("user-select:none on the controls of the %s page — without it a slow press on a"
                        + " button's own label starts a selection and the click never fires", name)
                .contains("user-select: none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "message", "admin"})
    void everyClientPageLetsAFieldOptBackIn(String name) throws IOException {
        // `user-select` INHERITS, so the block above reaches every <input> a <label> wraps — and on
        // iOS Safari an inherited `none` fights the caret: the field focuses, the keyboard opens,
        // and placing the caret in what is already typed does not work. The opt-out is part of the
        // fix rather than a detail of it, and it is the half a later tidy-up would drop first.
        assertThat(page(name))
                .as("fields on the %s page opt back into selection", name)
                .contains("user-select: text");
    }

    @Test
    void portalDoesNotLeaveAStickyHoverStandingInForPressedState() throws IOException {
        String portal = page("portal");
        // A touch device fakes :hover — the last-tapped control keeps the hover look until
        // something else is tapped, which is exactly the signal the client reads to decide whether
        // the tap landed. So hover is desktop-only and a press gets its own :active feedback.
        assertThat(portal).contains("@media (hover: hover)");
        assertThat(portal).contains("button:active, a.btn:active");
    }

    @Test
    void theTinyInfoDotIsStillATapTarget() throws IOException {
        // An 18 px dot sits beside a heading and cannot simply be grown to 44 px, so the hit area
        // is enlarged past the drawn circle instead. Dropping this leaves a control a thumb misses.
        assertThat(page("portal")).contains(".info-trigger::after, .info-panel-close::after");
    }
}
