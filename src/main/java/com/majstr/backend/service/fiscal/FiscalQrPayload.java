package com.majstr.backend.service.fiscal;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a Ukrainian fiscal receipt's QR code carries: the fiscal number of the cash register, the
 * receipt's number, its timestamp and its total. That quartet is exactly what the tax service's
 * lookup needs, and all four must match the stored receipt to the cent — a wrong sum answers
 * 400 «не вірна сума», so there is no guessing our way through a half-read code.
 *
 * <p>The payload is printed as the cabinet's check URL, so parsing is a query-string read. It is
 * deliberately tolerant about shape (full URL / bare query / leading '?') and about the two field
 * spellings paper actually uses for the date, because the QR is produced by dozens of РРО vendors
 * and we only ever read it — a payload we cannot parse is a soft "not recognized", never an error.</p>
 */
public record FiscalQrPayload(String fn, String id, BigDecimal sum, LocalDateTime issuedAt) {

    /** The format the lookup expects, whatever the QR itself printed. */
    private static final DateTimeFormatter LOOKUP_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("ddMMyyyy"),
    };
    private static final DateTimeFormatter[] TIME_FORMATS = {
            DateTimeFormatter.ofPattern("HHmmss"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HHmm"),
            DateTimeFormatter.ofPattern("HH:mm"),
    };

    /** The timestamp in the shape the lookup wants. */
    public String lookupDate() {
        return issuedAt.format(LOOKUP_DATE);
    }

    /** Plain-decimal total, the way the lookup's {@code sm} parameter is written. */
    public String lookupSum() {
        return sum.toPlainString();
    }

    /**
     * Read a scanned QR payload, or empty when this is not a fiscal receipt code we understand
     * (a link to something else, a torn read, a missing field).
     */
    public static Optional<FiscalQrPayload> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Map<String, String> q = query(raw);

        String fn = q.get("fn");
        String id = q.get("id");
        BigDecimal sum = decimal(q.get("sm"));
        LocalDateTime at = timestamp(q.get("date"), q.get("time"));
        if (fn == null || fn.isBlank() || id == null || id.isBlank() || sum == null || at == null) {
            return Optional.empty();
        }
        return Optional.of(new FiscalQrPayload(fn.trim(), id.trim(), sum, at));
    }

    /** Lower-cased parameter map; everything before a '?' is the address and is dropped. */
    private static Map<String, String> query(String raw) {
        String s = raw.trim();
        int mark = s.indexOf('?');
        if (mark >= 0) s = s.substring(mark + 1);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);

        Map<String, String> out = new HashMap<>();
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(raw.trim().replace(',', '.'));
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The date field sometimes carries the time too (some codes print one combined value and no
     * {@code time} at all), so a combined read is tried before the two are joined.
     */
    private static LocalDateTime timestamp(String date, String time) {
        if (date == null || date.isBlank()) return null;
        String d = date.trim();

        int space = d.indexOf(' ');
        if (space > 0 && (time == null || time.isBlank())) {
            return timestamp(d.substring(0, space), d.substring(space + 1));
        }
        if (d.length() > 8 && time == null) {
            // «20240115123045» — a date glued to a time, no separator.
            return timestamp(d.substring(0, 8), d.substring(8));
        }

        var day = parseDate(d);
        if (day == null) return null;
        var at = time == null || time.isBlank() ? java.time.LocalTime.MIDNIGHT : parseTime(time.trim());
        return at == null ? null : LocalDateTime.of(day, at);
    }

    private static java.time.LocalDate parseDate(String s) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return java.time.LocalDate.parse(s, f);
            } catch (java.time.format.DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    private static java.time.LocalTime parseTime(String s) {
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return java.time.LocalTime.parse(s, f);
            } catch (java.time.format.DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }
}
