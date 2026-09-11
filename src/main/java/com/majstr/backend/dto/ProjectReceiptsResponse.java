package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The object's receipts plus the two figures the screen shows above them (V129).
 *
 * @param items             newest paper first, undated on top
 * @param reimbursableTotal Σ of what the client is expected to pay back — the receivable
 * @param ownTotal          Σ of the receipts the master flipped to «моя витрата»; these are also
 *                          {@code object_expenses} rows, so they are counted there and never here
 * @param unpricedCount     how many receipts are saved but still carry no amount. Zero is a normal
 *                          state and so is a positive number — the photo is saved first and priced
 *                          afterwards, which is the whole point at the till
 */
public record ProjectReceiptsResponse(
        List<ProjectReceiptResponse> items,
        BigDecimal reimbursableTotal,
        BigDecimal ownTotal,
        long unpricedCount
) {}
