package com.majstr.backend.dto;

import java.math.BigDecimal;

/** One computed row of a split, before it becomes a real {@code ProjectPayment} — used both by
 *  the preview (nothing saved) and as the source of truth when the master confirms it. */
public record PaymentSplitRow(String purpose, BigDecimal amount) {}
