package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Edit an already-recorded receipt — amount/date/label only; which stage it closes is fixed at
 *  creation (re-linking would re-open the whole overflow question, deliberately not supported). */
public record PaymentReceiptEditRequest(
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100000000") BigDecimal amount,
        @NotNull LocalDate receivedAt,
        @Size(max = 255) String label,
        /**
         * Money the client paid BACK for material (V135). Carried here as well as on the create so
         * the flag has ONE door in and out — «Мої гроші» edits object payments in place, and a
         * second write path just for this boolean would be the thing that drifts. Since review B-65
         * the object economy reads it as well — a refund settles the materials axis instead of the
         * contract — so a wrong tick is corrected from either screen.
         *
         * <p>Three-valued on purpose, like V129's {@code reimbursable}: null = leave it alone. The
         * object economy's edit sheet has no refund switch and sends no such field, and this one
         * record serves both doors — as a primitive it silently cleared a flag set from the cash
         * screen and moved «Заробив» on a master who only retyped an amount.</p>
         */
        Boolean materialRefund
) {}
