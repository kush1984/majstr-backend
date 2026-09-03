package com.majstr.backend.service.importer;

import com.majstr.backend.entity.CatalogItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Matches a spoken position name against the master's OWN catalog. Deterministic and explainable —
 * no model call, same rule as {@link UnitNormalizer}: what fills in a price has to be something a
 * master can be told the reason for.
 *
 * <p>Two rungs. An exact match on the normalized name wins outright. Otherwise the two names are
 * compared as sets of word STEMS (Ukrainian inflects heavily — «шпалери» / «шпалер» / «шпалерами»
 * are one word), scored by Dice overlap.</p>
 *
 * <p><b>A tie is refused, not resolved.</b> The score must clear {@link #MIN_SCORE} <em>and</em>
 * beat the runner-up by {@link #MIN_MARGIN}; «Шпаклювання стін Q3» and «Шпаклювання стін Q4» score
 * identically against «шпаклівка стін», and picking either would put a price the master never
 * chose onto a line he is about to sign. No match is a visible flag on the review screen; a wrong
 * match is a number nobody looks at twice.</p>
 */
public final class CatalogMatcher {

    /** Dice overlap below this is not a match at all — two names sharing one common word. */
    static final double MIN_SCORE = 0.6;
    /** How far the winner must beat the runner-up. Anything closer is a guess between two rows. */
    static final double MIN_MARGIN = 0.1;
    /** Ukrainian inflects on the tail, so only the first few letters carry the meaning. FOUR, not
     *  five, and the difference is load-bearing: «стіни» and «стін» are the same word said two
     *  ways, and at five letters they stay two tokens — which made «штукатурити стіни по маяках»
     *  score exactly as high against the CEILING row as against the wall one, and a tie is refused. */
    private static final int STEM_LENGTH = 4;
    /** Shorter tokens are prepositions and conjunctions («та», «по», «з») — noise in an overlap. */
    private static final int MIN_TOKEN_LENGTH = 3;

    private CatalogMatcher() {
    }

    public static Optional<CatalogItem> match(String spokenName, List<CatalogItem> catalog) {
        if (spokenName == null || spokenName.isBlank() || catalog.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalize(spokenName);

        List<CatalogItem> exact = catalog.stream()
                .filter(c -> normalize(c.getName()).equals(normalized))
                .toList();
        if (exact.size() == 1) {
            return Optional.of(exact.getFirst());
        }
        if (exact.size() > 1) {
            return Optional.empty(); // the same wording under two units — his call, not ours
        }

        Set<String> spokenStems = stems(spokenName);
        if (spokenStems.isEmpty()) {
            return Optional.empty();
        }
        CatalogItem best = null;
        double bestScore = 0;
        double runnerUp = 0;
        for (CatalogItem candidate : catalog) {
            double score = dice(spokenStems, stems(candidate.getName()));
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (best == null || bestScore < MIN_SCORE || bestScore - runnerUp < MIN_MARGIN) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /** Lowercased, apostrophes dropped, everything else non-alphanumeric collapsed to one space. */
    static String normalize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == '\'' || c == '’' || c == 'ʼ' || c == '`') {
                continue; // «в'їзд» / «вʼїзд» are one word written two ways
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }
        return out.toString().trim();
    }

    private static Set<String> stems(String raw) {
        Set<String> stems = new LinkedHashSet<>();
        for (String token : normalize(raw).split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            // A digit-bearing token is never truncated: «Q3» and «60» ARE the distinguishing part,
            // and a stem that cut them would merge the very rows this matcher must keep apart.
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            if (hasDigit) {
                stems.add(token);
            } else if (token.length() >= MIN_TOKEN_LENGTH) {
                stems.add(token.length() > STEM_LENGTH ? token.substring(0, STEM_LENGTH) : token);
            }
        }
        return stems;
    }

    /** Dice coefficient: 2·|A∩B| / (|A|+|B|) — symmetric, so neither a long catalog name nor a
     *  long spoken one is rewarded for size alone. */
    private static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        List<String> shared = new ArrayList<>(a);
        shared.retainAll(b);
        return 2.0 * shared.size() / (a.size() + b.size());
    }
}
