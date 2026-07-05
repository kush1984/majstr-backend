package com.majstr.backend.dto;

/**
 * PRO checkout options. {@code autoRenew} = the master opted in to auto-renewal —
 * the card is tokenized and charged before each period ends. Optional body; absent
 * or false = a plain one-time payment.
 */
public record CheckoutRequest(boolean autoRenew) {}
