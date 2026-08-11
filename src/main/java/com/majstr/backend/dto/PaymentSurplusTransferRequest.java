package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** "На «{from.purpose}» отримано більше — перенести сюди як частково оплачену?" — moves an
 *  over-received stage's surplus onto a newly created (or any other) open stage, offered when the
 *  master creates a new planned stage while another one is sitting in RESERVE overpayment. */
public record PaymentSurplusTransferRequest(
        @NotNull UUID fromPaymentId,
        @NotNull UUID toPaymentId
) {}
