package com.majstr.backend.dto;

import com.majstr.backend.entity.BillingPeriod;

/**
 * PRO checkout options. {@code period} picks the tariff (MONTH | HALF_YEAR | YEAR;
 * absent = MONTH) — the server derives the amount from it, the client never sends a price.
 * {@code autoRenew} = the master opted in to auto-renewal; the card is tokenized and
 * charged for the SAME period before each cycle ends. Absent body = one-time MONTH.
 */
public record CheckoutRequest(BillingPeriod period, boolean autoRenew) {

    /** Null-safe period — a missing/legacy body defaults to the monthly tariff. */
    public BillingPeriod periodOrDefault() {
        return period != null ? period : BillingPeriod.MONTH;
    }
}
