package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edit an object receipt. The photo is set once at upload and never replaced — swap it by deleting
 * the row and adding it again.
 *
 * <p>{@code reimbursable} is the ONE economic decision in the whole feature, and it is deliberately
 * <b>three-valued</b>: {@code null} leaves the flag alone. That is what makes the ordinary «I read
 * the sum off the paper» save carry no opinion about whose money it was, while the one-tap flip
 * («це моя витрата») rides the same endpoint. The flip is what creates or removes the receipt's
 * {@code object_expenses} row — see {@code ProjectReceiptService.update}.</p>
 *
 * <p>{@code fiscalFn} / {@code fiscalId} are the printed identity read off the QR. They arrive here
 * rather than at upload because the photo is saved before anything is read off it.</p>
 */
public record ProjectReceiptRequest(
        @NotBlank @Size(max = 160) String label,
        @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") BigDecimal amount,
        LocalDate issuedAt,
        Boolean reimbursable,
        @Size(max = 64) String fiscalFn,
        @Size(max = 64) String fiscalId
) {}
