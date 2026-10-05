package com.majstr.backend.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLAUDE.md loads into EVERY session, so its size is a running cost, and the Architecture index is
 * the part that grows: one bullet per shipped round, and each one kept getting longer until the
 * file was 86 KB — more than double the 40 KB it was split at.
 *
 * <p>The split is the whole mechanism: a one-to-three-line bullet in CLAUDE.md carrying the
 * invariant and the identifiers, and an unabridged twin in
 * {@code docs/architecture-index-detail.md} carrying the reasoning. That only works while the two
 * files actually PAIR — and nothing noticed when a title drifted on one side, which is how half the
 * index came to have a twin nobody could find and the reasoning got re-written into CLAUDE.md
 * instead.</p>
 *
 * <p>So this asserts the three things the arrangement needs, and they are all mechanical: every
 * bullet has a twin BY TITLE in both directions, and no bullet is longer than a bullet. The
 * character cap is deliberately generous — it is there to catch a bullet growing into an essay, not
 * to make anyone count words.</p>
 */
class ArchitectureIndexPairingTest {

    /** A bullet may state an invariant with its identifiers; past this it is writing an essay. */
    private static final int MAX_BULLET_CHARS = 1_400;
    /** The budget CLAUDE.md was split at in 2026-09. Generous, and it still has to hold. */
    private static final int MAX_CLAUDE_CHARS = 60_000;

    private static final Pattern BULLET = Pattern.compile("^- \\*\\*(.+?)\\*\\*", Pattern.MULTILINE);

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /** Every top-level `- **Title** …` bullet of a document, in order. */
    private static List<String> titles(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = BULLET.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String indexOf(String claude) {
        return claude.substring(claude.indexOf("## Architecture index"));
    }

    @Test
    void everyIndexBulletHasAnUnabridgedTwin() throws IOException {
        Set<String> detail = new LinkedHashSet<>(titles(read("docs/architecture-index-detail.md")));
        List<String> index = titles(indexOf(read("CLAUDE.md")));

        assertThat(index).as("the index was found at all").hasSizeGreaterThan(50);
        assertThat(index.stream().filter(t -> !detail.contains(t)).toList())
                .as("these index bullets have no twin in docs/architecture-index-detail.md — put the"
                        + " long form there (paired BY TITLE) rather than growing the bullet")
                .isEmpty();
    }

    @Test
    void everyUnabridgedBulletStillHasItsIndexEntry() throws IOException {
        Set<String> index = new LinkedHashSet<>(titles(indexOf(read("CLAUDE.md"))));
        List<String> detail = titles(read("docs/architecture-index-detail.md"));

        assertThat(detail.stream().filter(t -> !index.contains(t)).toList())
                .as("these long forms name nothing in the index — the bullet was renamed or dropped,"
                        + " which is how a twin becomes unfindable")
                .isEmpty();
    }

    @Test
    void noIndexBulletGrowsIntoAnEssay() throws IOException {
        String index = indexOf(read("CLAUDE.md"));
        List<String> tooLong = new ArrayList<>();
        for (String line : index.split("\n")) {
            if (line.startsWith("- **") && line.length() > MAX_BULLET_CHARS) {
                tooLong.add(line.length() + " chars: " + line.substring(0, 70));
            }
        }
        assertThat(tooLong)
                .as("a bullet keeps the invariant and the identifiers; the reasoning belongs in the"
                        + " unabridged twin, which costs nothing because it does not load every session")
                .isEmpty();
    }

    @Test
    void claudeMdStaysWithinTheBudgetItWasSplitFor() throws IOException {
        assertThat(read("CLAUDE.md").length())
                .as("CLAUDE.md loads into every session; it was split from its detail file to stay"
                        + " small and had drifted to 86k before this cap existed")
                .isLessThan(MAX_CLAUDE_CHARS);
    }
}
