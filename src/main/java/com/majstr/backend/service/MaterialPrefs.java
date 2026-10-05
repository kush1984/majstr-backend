package com.majstr.backend.service;

import com.majstr.backend.entity.MaterialPrefKey;
import com.majstr.backend.exception.MaterialPrefValidationException;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * What a stored habit is allowed to say — ONE definition, for the write and for the read (B-19).
 *
 * <p>Two separate failures met here. The first is a Ukrainian keyboard: a master types «2,5», the
 * screen says «збережено», and then {@code new BigDecimal("2,5")} throws on the READ path, where the
 * miss is swallowed and treated as «no answer». So his correction was stored, confirmed, and had no
 * effect on any calculation — the worst shape a settings screen can take, because nothing on it is
 * wrong.</p>
 *
 * <p>The second is that nothing was bounded. {@code PAINT_COVERAGE} is m² per litre and divides:
 * {@code 0.5} multiplies every paint figure by eighteen, and the shopping list then sends him for
 * 54 litres of paint for one room. A habit is a small correction to a shipped norm, not a free
 * variable — so each key states the band a real answer falls in, and anything outside it is a 400
 * rather than a number that quietly ruins one list.</p>
 *
 * <p>The write stores the CANONICAL form ({@code 2.5}), so nothing new needs the comma. The read
 * stays tolerant anyway, because rows stored before this class exists still carry «2,5».</p>
 */
final class MaterialPrefs {

    /** «1200x2500», «1200×2500», «1200*2500» — two positive integers and a separator. */
    private static final Pattern SHEET = Pattern.compile("^\\d{1,5}\\s*[x×*]\\s*\\d{1,5}$");

    private MaterialPrefs() {
    }

    /**
     * The value to store for {@code key}, or a 400 if the master cannot have meant it.
     *
     * @param raw already trimmed and known non-blank (a blank value DELETES the preference, which is
     *            how a habit is forgotten — that decision belongs to the caller, not here)
     */
    static String canonical(MaterialPrefKey key, String raw) {
        if (key == MaterialPrefKey.GKL_SHEET) {
            String sheet = raw.toLowerCase(Locale.ROOT).replace(" ", "").replace('×', 'x').replace('*', 'x');
            if (!SHEET.matcher(raw.toLowerCase(Locale.ROOT)).matches()) {
                throw invalid();
            }
            return sheet;
        }
        BigDecimal value = number(raw);
        if (value == null) {
            throw invalid();
        }
        return switch (key) {
            // 0 is a legitimate answer: «я не беру запас» is a habit like any other.
            case WASTE_PERCENT -> inRange(value, "0", "100");
            // Under 3 m²/l is thicker than any coating in the dictionary, over 20 is a primer's
            // spec misread as paint. Both directions scale every paint figure linearly.
            case PAINT_COVERAGE -> inRange(value, "3", "20");
            case PAINT_COATS -> whole(inRange(value, "1", "4"));
            // A 1 mm joint is the narrowest rectified tile; 20 mm is a paving slab.
            case TILE_JOINT_MM -> inRange(value, "1", "20");
            case GKL_SHEET -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * A stored numeric habit, comma tolerant, {@code null} when it cannot be read.
     *
     * <p>The read path's own answer to an unreadable value stays «no answer» — a calculation must
     * not fail because a row predating {@link #canonical} holds something odd. What changed is that
     * a NEW value can no longer get in that state.</p>
     */
    static BigDecimal number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String inRange(BigDecimal value, String min, String max) {
        if (value.compareTo(new BigDecimal(min)) < 0 || value.compareTo(new BigDecimal(max)) > 0) {
            throw invalid();
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String whole(String value) {
        BigDecimal number = new BigDecimal(value);
        if (number.stripTrailingZeros().scale() > 0) {
            throw invalid();
        }
        return number.toBigInteger().toString();
    }

    private static MaterialPrefValidationException invalid() {
        return new MaterialPrefValidationException("error.material-pref.invalid");
    }
}
