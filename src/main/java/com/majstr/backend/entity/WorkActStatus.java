package com.majstr.backend.entity;

/**
 * Lifecycle of a work act (acts iteration). Mirrors the estimate's own DRAFT→SENT→SIGNED shape,
 * plus REJECTED for a client who declines.
 *
 * <ul>
 *   <li>{@link #DRAFT} — editable; the master is still filling it in.</li>
 *   <li>{@link #SENT} — shared with the client, awaiting their signature.</li>
 *   <li>{@link #SIGNED} — immutable; the client accepted it. Only SIGNED acts count toward
 *       cumulative progress and «Прийнято актами».</li>
 *   <li>{@link #REJECTED} — the client turned it down.</li>
 * </ul>
 */
public enum WorkActStatus {
    DRAFT,
    SENT,
    SIGNED,
    REJECTED
}
