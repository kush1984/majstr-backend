package com.majstr.backend.service;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The NBU payment QR («QR-код для кредитового переказу», format 002) as a link: every Ukrainian
 * bank's app reads it, and opening it in a phone hands the transfer to the bank app with recipient,
 * IBAN, code and purpose filled in. Format 002 rather than 003 because 003 is still not read by
 * every bank.
 *
 * <p>The payload is thirteen lines joined by LF, Windows-1251, Base64URL without padding, behind
 * {@code https://bank.gov.ua/qr/}: service tag, version, encoding, function, (BIC — reserved),
 * recipient, IBAN, amount, recipient code, (two reserved), purpose, (display — reserved).</p>
 *
 * <p>No amount: what a client still owes differs per portal, and the client types it in his bank.
 * Text is held to what Windows-1251 can carry (codes 32–255 minus 127, 152 and 160); anything else —
 * an emoji in an object name, a «₴» — is dropped rather than sent as a «?» that a bank may refuse.</p>
 */
public final class NbuPaymentQr {

    static final String PREFIX = "https://bank.gov.ua/qr/";
    static final Pattern UA_IBAN = Pattern.compile("UA\\d{27}");
    /** РНОКПП (10 digits) or ЄДРПОУ (8). */
    static final Pattern RECIPIENT_CODE = Pattern.compile("\\d{8}|\\d{10}");
    static final int MAX_RECIPIENT = 140;
    static final int MAX_PURPOSE = 420;

    private static final Charset CP1251 = Charset.forName("windows-1251");

    private NbuPaymentQr() {}

    /** Null unless every mandatory field is usable: a QR a bank app rejects is worse than none. */
    public static String link(String recipient, String iban, String recipientCode, String purpose) {
        String account = iban == null ? "" : iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String code = recipientCode == null ? "" : recipientCode.replaceAll("\\s+", "");
        String name = clean(recipient, MAX_RECIPIENT);
        String text = clean(purpose, MAX_PURPOSE);
        if (!UA_IBAN.matcher(account).matches() || !RECIPIENT_CODE.matcher(code).matches()
                || name.isEmpty() || text.isEmpty()) {
            return null;
        }
        String payload = String.join("\n",
                "BCD", "002", "2", "UCT", "", name, account, "", code, "", "", text, "");
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(CP1251));
    }

    /** One line, Windows-1251-safe, trimmed to the field's length. */
    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp)) {
                out.append(' ');
            } else if (encodable(cp)) {
                out.appendCodePoint(cp);
            }
        });
        String one = out.toString().replaceAll(" {2,}", " ").trim();
        return one.length() > max ? one.substring(0, max).trim() : one;
    }

    private static boolean encodable(int cp) {
        if (cp < 32 || cp == 127 || Character.charCount(cp) > 1) {
            return false;
        }
        byte[] b = String.valueOf((char) cp).getBytes(CP1251);
        if (b.length != 1) {
            return false;
        }
        int v = b[0] & 0xFF;
        // A character the charset cannot map comes back as «?» (63); only a real «?» may stay.
        return (v != '?' || cp == '?') && v != 127 && v != 152 && v != 160;
    }
}
