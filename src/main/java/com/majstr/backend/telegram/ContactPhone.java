package com.majstr.backend.telegram;

/**
 * The number a master shares with the bot, in one shape (V226). Telegram sends it as international digits,
 * with or without a «+» («380501234567», «+380501234567»); a Ukrainian number is kept as {@code +380XXXXXXXXX}.
 */
public final class ContactPhone {

    private ContactPhone() {}

    /** {@code +380…} for a Ukrainian number, {@code +<digits>} for another country; null when it is not a phone. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("380")) {
            return "+" + digits;
        }
        if (digits.length() == 10 && digits.startsWith("0")) {
            return "+38" + digits;
        }
        // E.164 allows up to 15 digits; fewer than 8 is not a number anyone can call.
        return digits.length() >= 8 && digits.length() <= 15 ? "+" + digits : null;
    }
}
