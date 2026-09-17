package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The object's receipts plus the two figures the screen shows above them (V129).
 *
 * @param items             newest paper first, undated on top
 * @param reimbursableTotal Σ of what the client is expected to pay back — the receivable. A receipt
 *                          already billed on a signed act is OUT of it (B-04): that money now sits
 *                          in «За договором» under a document the client signed, and a debt shown
 *                          in two places gets asked for twice. The ROW stays in {@code items},
 *                          saying which act took it — this is the same filter
 *                          {@code ProjectReceiptRepository.sumReimbursable} applies, and the two
 *                          describe one number on two screens, so they must never differ
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
