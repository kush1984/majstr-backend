package com.majstr.backend.entity;

/**
 * Subscription period a payment buys. The concrete price and day count for each
 * period live in {@code BillingProperties} (server-side only — the client sends
 * the period, never an amount).
 */
public enum BillingPeriod {
    MONTH,
    HALF_YEAR,
    YEAR
}
