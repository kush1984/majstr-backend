package com.majstr.backend.exception;

/**
 * A write against an object receipt that a SIGNED act has already billed to the client (review
 * B-32), refused with 409 {@code PROJECT_RECEIPT_BILLED_ON_ACT}.
 *
 * <p>Once {@code project_receipt.billed_on_act_id} is stamped (V134), the paper's money has left
 * this table: the act's ADDENDUM carries it into «За договором», and — when the act posts its
 * receipts as expenses — the act's own row is the cost record. Every money-bearing edit from here
 * therefore rewrites history under a signature:</p>
 * <ul>
 *   <li>flipping to «клієнт відшкодовує» DELETES the only record of that cost when the act does not
 *       post expenses, and profit is overstated by the whole receipt;</li>
 *   <li>flipping to «моя витрата» posts a SECOND cost beside the act's own;</li>
 *   <li>a deleted or re-priced receipt moves a figure the client has already signed for.</li>
 * </ul>
 *
 * <p>What the paper SAYS is still correctable — label, date and photo are not money — so only the
 * amount, the «whose money» flip and the delete are refused.</p>
 */
public class ProjectReceiptBilledException extends RuntimeException {
    public ProjectReceiptBilledException() {
        super("error.project-receipt.billed-on-act");
    }
}
