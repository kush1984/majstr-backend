package com.majstr.backend.exception;

/**
 * An {@code object_expenses} row an object receipt owns was edited or deleted directly in the
 * journal (V129). The receipt and the row are one fact — the receipt mirrors its amount, label and
 * date onto it — so the write is refused with 409 {@code EXPENSE_LINKED_TO_RECEIPT} and the master
 * is sent to the receipt, where «це моя витрата» can also be turned off outright.
 */
public class ExpenseLinkedToReceiptException extends RuntimeException {
    public ExpenseLinkedToReceiptException() {
        super("error.expense.linked-to-receipt");
    }
}
