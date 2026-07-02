package com.majstr.backend.dto;

/**
 * Result of starting a PRO checkout: the URL to send the payer to. In production
 * this is the monobank hosted payment page ({@code pay.monobank.ua/...}); in dev
 * (no merchant token) PRO is granted immediately and this is the app's return URL.
 */
public record CheckoutResponse(String pageUrl) {}
