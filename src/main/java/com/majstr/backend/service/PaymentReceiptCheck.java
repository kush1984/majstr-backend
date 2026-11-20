package com.majstr.backend.service;

import com.majstr.backend.service.PaymentReceiptReader.Read;
import com.majstr.backend.service.PaymentReceiptReader.Status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Does a client's receipt back his «Я оплатив» (V217)? The decision that writes money into the
 * master's object without his tap, so it is plain code over what {@link PaymentReceiptReader} read,
 * and it says why in the master's words whenever the answer is no.
 *
 * <p>All of: the bank says the transfer went through (or says nothing); the transfer amount equals,
 * to the kopeck, what the client typed; the recipient is the master — his IBAN in full, or his
 * card's last four (banks print the card masked); dated within the last {@link #MAX_AGE_DAYS} days
 * and not in the future. A match proves the client SENT the money to these details — not that it
 * arrived, and not that the picture is genuine; the master can still delete the payment.</p>
 */
public final class PaymentReceiptCheck {

    static final int MAX_AGE_DAYS = 7;
    static final String MATCHED = "Квитанція збігається: сума, отримувач і дата.";

    private static final Set<String> HRYVNIA = Set.of("UAH", "ГРН", "₴", "ГРН.");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private PaymentReceiptCheck() {}

    /** {@code note} is shown to the master as is; {@code receivedAt} is set only on a match. */
    public record Outcome(boolean matched, String note, LocalDate receivedAt) {
        static Outcome no(String note) {
            return new Outcome(false, note, null);
        }
    }

    /**
     * @param masterIban the master's IBAN without spaces, upper case, or null
     * @param masterCard the master's card, digits only, or null
     */
    public static Outcome check(Read read, BigDecimal claimed, String masterIban, String masterCard,
                                LocalDate today) {
        if (read.status() == Status.FAILED) {
            return Outcome.no("Банк у квитанції пише, що переказ не пройшов.");
        }
        if (read.status() == Status.IN_PROGRESS) {
            return Outcome.no("У квитанції переказ ще в обробці.");
        }
        if (read.amount() == null) {
            return Outcome.no("У квитанції не видно суми переказу.");
        }
        if (read.currency() != null && !HRYVNIA.contains(read.currency().toUpperCase(Locale.ROOT))) {
            return Outcome.no("Переказ у квитанції не в гривнях (" + read.currency() + ").");
        }
        if (read.amount().compareTo(claimed) != 0) {
            return Outcome.no("У квитанції " + PaymentNoticeService.hryvnias(read.amount())
                    + ", а клієнт вказав " + PaymentNoticeService.hryvnias(claimed) + ".");
        }
        boolean ibanMatches = masterIban != null && masterIban.equals(read.recipientIban());
        boolean cardMatches = masterCard != null && read.recipientCardLast4() != null
                && masterCard.endsWith(read.recipientCardLast4());
        if (!ibanMatches && !cardMatches) {
            return Outcome.no(read.recipientIban() == null && read.recipientCardLast4() == null
                    ? "У квитанції не видно рахунку чи картки отримувача."
                    : "У квитанції інший отримувач, ніж ваші реквізити.");
        }
        if (read.date() == null) {
            return Outcome.no("У квитанції не видно дати.");
        }
        if (read.date().isAfter(today)) {
            return Outcome.no("Дата у квитанції ще не настала (" + DAY.format(read.date()) + ").");
        }
        if (read.date().isBefore(today.minusDays(MAX_AGE_DAYS))) {
            return Outcome.no("Квитанція від " + DAY.format(read.date()) + " — давніша за тиждень.");
        }
        return new Outcome(true, MATCHED, read.date());
    }
}
