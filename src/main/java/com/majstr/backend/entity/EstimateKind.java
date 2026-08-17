package com.majstr.backend.entity;

/**
 * Whether an estimate is a normal one the master authored, or an auto-generated addendum
 * (acts iteration).
 *
 * <ul>
 *   <li>{@link #REGULAR} — the default; every estimate created the usual ways.</li>
 *   <li>{@link #ADDENDUM} — «Додаткові роботи до акта № N», created automatically (SIGNED,
 *       counting) when a work act is signed with extra positions not in any estimate. It keeps
 *       «Прийнято актами» from exceeding «За договором». Hidden from the estimate pickers and the
 *       Кошториси list — it shows as a sub-line of the act that spawned it.</li>
 * </ul>
 */
public enum EstimateKind {
    REGULAR,
    ADDENDUM
}
