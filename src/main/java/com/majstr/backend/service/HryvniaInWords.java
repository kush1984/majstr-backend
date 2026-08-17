package com.majstr.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ukrainian «сума прописом» for a work-act PDF (acts iteration). No such utility existed, so it is
 * written from scratch: the hryvnia part is spelled out with correct feminine numerals and noun
 * declension («одна тисяча п'ятсот гривень», «двадцять одна гривня»), and kopecks are shown as
 * digits with their own declension («00 копійок», «01 копійка», «50 копійок»).
 *
 * <p>Gender per group: hryvnia and «тисяча» are feminine (одна/дві), «мільйон»/«мільярд» masculine
 * (один/два). Declension follows the standard last-digit rule, with 11–14 always the «many» form.</p>
 */
final class HryvniaInWords {

    private HryvniaInWords() {
    }

    private static final String[] ONES_MASC =
            {"", "один", "два", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять"};
    private static final String[] ONES_FEM =
            {"", "одна", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять"};
    private static final String[] TEENS =
            {"десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять",
             "п'ятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "дев'ятнадцять"};
    private static final String[] TENS =
            {"", "", "двадцять", "тридцять", "сорок", "п'ятдесят",
             "шістдесят", "сімдесят", "вісімдесят", "дев'яносто"};
    private static final String[] HUNDREDS =
            {"", "сто", "двісті", "триста", "чотириста", "п'ятсот",
             "шістсот", "сімсот", "вісімсот", "дев'ятсот"};

    private static final String[] HRYVNIA = {"гривня", "гривні", "гривень"};
    private static final String[] KOPECK = {"копійка", "копійки", "копійок"};
    private static final String[] THOUSAND = {"тисяча", "тисячі", "тисяч"};
    private static final String[] MILLION = {"мільйон", "мільйони", "мільйонів"};
    private static final String[] MILLIARD = {"мільярд", "мільярди", "мільярдів"};

    /** e.g. 1500.05 → «Одна тисяча п'ятсот гривень 05 копійок». */
    static String format(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        long integer = value.longValue();
        int kopecks = value.remainder(BigDecimal.ONE).movePointRight(2).abs().intValueExact();

        StringBuilder sb = new StringBuilder(integerWords(integer));
        sb.append(' ').append(pick((int) (integer % 100), (int) (integer % 10), HRYVNIA));
        sb.append(' ').append(String.format("%02d", kopecks));
        sb.append(' ').append(pick(kopecks % 100, kopecks % 10, KOPECK));
        return capitalize(sb.toString());
    }

    private static String integerWords(long n) {
        if (n == 0) {
            return "нуль";
        }
        int units = (int) (n % 1000);
        int thousands = (int) ((n / 1000) % 1000);
        int millions = (int) ((n / 1_000_000) % 1000);
        int milliards = (int) ((n / 1_000_000_000) % 1000);

        StringBuilder sb = new StringBuilder();
        appendGroup(sb, milliards, false, MILLIARD);
        appendGroup(sb, millions, false, MILLION);
        appendGroup(sb, thousands, true, THOUSAND);
        if (units != 0) {
            append(sb, triplet(units, true)); // feminine — hryvnia is feminine
        }
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, int triplet, boolean feminine, String[] noun) {
        if (triplet == 0) {
            return;
        }
        append(sb, triplet(triplet, feminine));
        append(sb, pick(triplet % 100, triplet % 10, noun));
    }

    /** Words for 0..999 with the given gender for the 1/2 place. */
    private static String triplet(int n, boolean feminine) {
        StringBuilder sb = new StringBuilder();
        int h = n / 100;
        int rem = n % 100;
        int t = rem / 10;
        int u = rem % 10;
        if (h > 0) {
            append(sb, HUNDREDS[h]);
        }
        if (t == 1) {
            append(sb, TEENS[u]);
        } else {
            if (t > 1) {
                append(sb, TENS[t]);
            }
            if (u > 0) {
                append(sb, feminine ? ONES_FEM[u] : ONES_MASC[u]);
            }
        }
        return sb.toString();
    }

    /** Standard Ukrainian declension: 11–14 → many; last digit 1 → one; 2–4 → few; else many. */
    private static String pick(int lastTwo, int lastDigit, String[] forms) {
        if (lastTwo >= 11 && lastTwo <= 14) {
            return forms[2];
        }
        if (lastDigit == 1) {
            return forms[0];
        }
        if (lastDigit >= 2 && lastDigit <= 4) {
            return forms[1];
        }
        return forms[2];
    }

    private static void append(StringBuilder sb, String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(word);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
