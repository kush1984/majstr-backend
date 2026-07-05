package com.majstr.backend.entity;

/**
 * How a {@link Payment} was initiated: a manual {@link #CHECKOUT} (the master paid
 * on the hosted page) vs an {@link #AUTO_RENEW} merchant-initiated token charge
 * (the scheduled auto-renewal). Lets the admin tell them apart and drives the
 * receipt vs retry handling.
 */
public enum PaymentKind {
    CHECKOUT,
    AUTO_RENEW
}
