package com.majstr.backend.dto;

import com.majstr.backend.entity.CashCategory;
import com.majstr.backend.entity.CashDirection;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Write one line of the master's own money (V135).
 *
 * <p><b>Adding here NEVER asks about an object</b> (master's ruling, round 3: «я хочу, щоб
 * гроші/доходи зразу брались з обʼєктів, а не щось там вибиралось»). Money that belongs to an object
 * arrives on the READ path all by itself — it is already in that object's journal. What he types
 * here is what no object knows about: fuel, tools, taxes, income for work that closed without an
 * act. An earlier round offered an object picker that ROUTED the row into that object's journal; it
 * was removed because it read as a required step in front of a screen that already pulls
 * everything.</p>
 *
 * <p>{@code happenedOn} may be back-dated freely — «не завжди майстер вносить одразу». The TIME is
 * never sent: it is stamped server-side at entry, because its only job is ordering rows inside a day
 * and a time picker on every entry is friction for nothing else.</p>
 *
 * <p>The same record is also the EDIT body for a row of any kind, including an object's own payment
 * or expense — {@code kind} says which table the row lives in. Fields that table has no place for
 * are ignored rather than refused: a payment has no category, and its direction is decided by being
 * a payment at all.</p>
 *
 * @param materialRefund only meaningful on an INCOME; a DB CHECK refuses it on a personal expense
 * @param kind           required on an edit, ignored on a create (which is always PERSONAL)
 */
public record CashEntryRequest(
        @NotNull CashDirection direction,
        /**
         * At least a kopeck, and at most two decimals (review B-37). The old
         * {@code > 0.0} let {@code 0.004} through, which then became {@code 0.00} at
         * {@code setScale(2)}: a personal row and an object payment hit their {@code CHECK (amount > 0)}
         * as a 500 with a Sentry event, while an object EXPENSE saved happily as 0.00 (V42 allows
         * {@code >= 0}) — a cost row worth nothing that the master then has to find and delete.
         * {@code @Digits} refuses the sub-kopeck figure rather than rounding it, because 0.004 is not
         * something a master typed on purpose. This is also the ONLY door for the object-row edits:
         * {@code update} builds {@code PaymentReceiptEditRequest}/{@code ExpenseRequest} by hand, so
         * their own constraints never run.
         */
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2)
        @DecimalMax("100000000") BigDecimal amount,
        /** Optional by design — a master at the wheel will not pick one. */
        CashCategory category,
        @Size(max = 500) String note,
        /** Absent = today, resolved in Europe/Kyiv (never the server's UTC idea of today). */
        LocalDate happenedOn,
        // A wrapper, required (review B-104): as a primitive, an omitted field read as «false» and an
        // edit silently un-ticked a refund — which moves the object's «Залишилось» (B-65). A boolean
        // that reverses where money lands is never defaulted by omission.
        @NotNull Boolean materialRefund,
        CashEntryKind kind
) {
    /**
     * A category has a DIRECTION (review B-52). «Аванс» is money in and «Пальне» is money out, and
     * the pair was accepted either way round, so one mistyped row sat in the month's feed labelled
     * as its own opposite. OTHER belongs to both on purpose — it is the answer for «I do not want to
     * pick one» — and a null category is not an error at all (a master at the wheel will not pick).
     */
    @jakarta.validation.constraints.AssertTrue(
            message = "category does not belong to this direction")
    public boolean isCategoryOfThisDirection() {
        if (category == null || direction == null || category == CashCategory.OTHER) {
            return true;
        }
        return switch (category) {
            case ADVANCE, WORK -> direction == CashDirection.INCOME;
            case MATERIALS, CREW, FUEL, TOOLS, TAXES -> direction == CashDirection.EXPENSE;
            case OTHER -> true;
        };
    }

    /**
     * Money that has not moved yet is not a cash row (review B-52).
     *
     * <p>Back-dating is free and deliberate — «не завжди майстер вносить одразу» — but there was no
     * bound the other way, and a row dated 2027 sits outside every period the screen can show while
     * still counting in nothing. Tomorrow is allowed rather than today: the date arrives from a
     * device whose clock and zone are its own, and refusing a row because a phone is eight hours
     * ahead would be a failure the master cannot act on.</p>
     */
    @jakarta.validation.constraints.AssertTrue(message = "happenedOn cannot be in the future")
    public boolean isHappenedOnNotInTheFuture() {
        return happenedOn == null || !happenedOn.isAfter(
                LocalDate.now(com.majstr.backend.config.LocalizationConfig.ZONE).plusDays(1));
    }
}
