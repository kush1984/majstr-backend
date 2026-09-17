package com.majstr.backend.dto;

/**
 * Which of the two receipt tables a cross-reference points at (review item B-04).
 *
 * <p>The product deliberately keeps them apart — {@code project_receipt} is the paper photographed
 * at the till, {@code work_act_receipt} is the paper re-billed on a document and frozen into its
 * {@code doc_hash} — so a warning about the same paper filed twice has to say WHERE the twin is.</p>
 */
public enum ReceiptKind {
    OBJECT,
    ACT
}
