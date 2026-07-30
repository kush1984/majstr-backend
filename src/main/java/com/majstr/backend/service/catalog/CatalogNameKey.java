package com.majstr.backend.service.catalog;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reduces a catalog position's name to a comparison key, so "the same work written differently"
 * can be recognised.
 *
 * <p><b>Why this exists.</b> V50's import claimed a punctuation-insensitive dedupe and still
 * duplicated 100+ positions, because the rows it was comparing against had been stored with
 * punctuation <i>and connecting words</i> stripped — two strings that no amount of punctuation
 * tolerance could reconcile. V71 then had to collapse those groups by hand, one explicit
 * statement per position, 498 lines of it. This is that rule written down once so it can be
 * applied instead of retyped.
 *
 * <p><b>Deliberately not fuzzy.</b> No edit distance, no stemming, no "one word spelled
 * differently". Those merge things that only look alike, and a wrong merge in a catalog is
 * invisible until a master prices the wrong work. What is here is only what can be defended:
 * case, punctuation, connecting words, and word order — all of which provably do not change
 * which job is meant. Anything beyond that is a human decision, which is exactly why the admin
 * screen shows candidates rather than merging them.
 *
 * <p><b>One implementation, in Java, on purpose.</b> The obvious alternative is a SQL expression
 * so the grouping happens in the database. That would be a second copy of the rule, and this
 * project already carries one mirrored formula (`MeasurementCalc` ↔ `measurementCalc.ts`) whose
 * standing cost is "change them in pairs or they drift". SQL returns raw rows; the rule lives
 * here.
 */
public final class CatalogNameKey {

    /**
     * Words that carry no identity for a position name. Prepositions and conjunctions only —
     * nothing that could distinguish two jobs. «Монтаж труб <b>у</b> стіні» and «Монтаж труб
     * стіни» are the same work; «Монтаж <b>чорнових</b> труб» is not, so adjectives stay.
     */
    private static final Set<String> CONNECTORS = Set.of(
            "і", "й", "та", "а", "але", "або", "чи",
            "в", "у", "на", "з", "із", "зі", "для", "під", "по", "при", "про",
            "за", "над", "перед", "між", "без", "через", "о", "об", "та-й");
    // «від» and «до» are NOT here, though they are prepositions. In a catalog name they carry
    // a range: «шириною ДО 30см» and «шириною ВІД 30см» are opposite jobs at different prices,
    // and dropping the word made them the same key. Found on the real tiling catalog, where it
    // reported two legitimate shelf positions as duplicates of each other.

    private CatalogNameKey() {
    }

    /**
     * The comparison key: lowercased, punctuation dropped, connectors removed, words sorted.
     *
     * <p>Sorting is what makes «Демонтаж плитки настінної» and «Демонтаж настінної плитки» meet.
     * It also means the key is NOT readable — never show it to anyone, show the original name.
     *
     * @return the key, or an empty string for a name that is blank or entirely connectors
     */
    public static String of(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String cleaned = name.toLowerCase(Locale.ROOT)
                // Everything that is not a letter or digit becomes a separator. Covers commas,
                // slashes, brackets, dashes, the «×» in dimensions, and the non-breaking spaces
                // that arrive from Excel paste.
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                // Split letter↔digit runs, so «Ø25» and «Ø 25» meet. Needed because «Ø» is a
                // Unicode LETTER, not punctuation — the rule above leaves «ø25» glued while the
                // spaced spelling becomes two tokens, and the two never match. Same for «ВВГ3х2.5»
                // against «ВВГ 3х2.5». It cannot over-merge: «Автомат 16А» and «Автомат 25А» still
                // differ by their digits.
                .replaceAll("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        return Arrays.stream(cleaned.split(" "))
                .filter(w -> !w.isEmpty())
                .filter(w -> !CONNECTORS.contains(w))
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /** True when two names denote the same position under {@link #of}. Blank never matches. */
    public static boolean sameWork(String a, String b) {
        String ka = of(a);
        return !ka.isEmpty() && ka.equals(of(b));
    }
}
