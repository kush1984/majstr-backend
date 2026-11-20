package com.majstr.backend.service;

import com.majstr.backend.dto.PayTo;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentValidationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Where a client pays the master (V217): the checks on save and the portal's {@link PayTo}.
 *
 * <p>The two payout fields are checked hard, unlike the document requisites: a client sends money to
 * them. A card number with one wrong digit fails Luhn instead of reaching a stranger; a link must be
 * https with a host, so «javascript:» or a bare word can never sit behind the portal's button.</p>
 */
public final class PayoutDetails {

    static final int MAX_LINK = 500;

    private PayoutDetails() {}

    /** Digits only, or null for blank; refuses anything that is not a Luhn-valid 13–19 digit number. */
    public static String normalizeCard(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{13,19}") || !luhn(digits)) {
            throw new PaymentValidationException("error.payout.card-invalid");
        }
        return digits;
    }

    /** The trimmed link, or null for blank; refuses anything that is not https with a host. */
    public static String normalizeLink(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String link = raw.trim();
        try {
            URI uri = new URI(link);
            if (link.length() > MAX_LINK || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new PaymentValidationException("error.payout.link-invalid");
            }
        } catch (URISyntaxException e) {
            throw new PaymentValidationException("error.payout.link-invalid");
        }
        return link;
    }

    /** What the portal offers for this object, or null when the master filled no way to pay. */
    public static PayTo of(User owner, Project project) {
        String card = owner.getPayoutCard();
        String iban = blankToNull(owner.getIban());
        String link = owner.getPayoutLink();
        if (card == null && iban == null && link == null) {
            return null;
        }
        String recipient = blankToNull(owner.getLegalName()) != null ? owner.getLegalName().trim()
                : blankToNull(owner.getFullName());
        // «’» (U+2019), not «ʼ»: the QR travels in Windows-1251, which has the one and not the other.
        String purpose = "Оплата за ремонтні роботи, об’єкт «" + project.getName() + "»";
        return new PayTo(
                recipient,
                card,
                iban == null ? null : iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT),
                iban == null ? null : NbuPaymentQr.link(recipient, iban, owner.getTaxId(), purpose),
                NbuPaymentQr.clean(purpose, NbuPaymentQr.MAX_PURPOSE),
                link,
                link == null ? null : URI.create(link).getHost());
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
