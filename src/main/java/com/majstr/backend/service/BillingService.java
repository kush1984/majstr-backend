package com.majstr.backend.service;

import com.majstr.backend.billing.MonobankClient;
import com.majstr.backend.billing.MonobankSignatureVerifier;
import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentProvider;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing via monobank acquiring. Phase 1: a PRO purchase is a
 * one-time payment that grants PRO for {@code proDays} (renew by a fresh checkout).
 *
 * <p><b>Trust model:</b> {@link #checkout} only creates an invoice + a PENDING
 * payment; PRO is granted <b>only</b> by {@link #handleWebhook} after the monobank
 * webhook signature is verified and the amount matches. The webhook is idempotent
 * on the invoice id, so a repeated success never double-extends PRO.</p>
 *
 * <p><b>Dev mode:</b> with no {@code MONOBANK_TOKEN} ({@link BillingProperties#isConfigured()}),
 * checkout simulates a paid invoice and grants PRO immediately — so the flow is
 * buildable/testable without a merchant account (mirrors the email/push env-gating).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private static final int UAH = 980;

    private final BillingProperties props;
    private final MonobankClient monobankClient;
    private final MonobankSignatureVerifier signatureVerifier;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CheckoutResponse checkout(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Payment payment = paymentRepository.save(Payment.builder()
                .userId(userId)
                .provider(PaymentProvider.MONOBANK)
                .amount(props.proPrice())
                .ccy(UAH)
                .status(PaymentStatus.PENDING)
                .plan(Plan.PRO)
                .days(props.proDays())
                .build());

        if (!props.isConfigured()) {
            if (!props.allowDevSimulation()) {
                // Prod safety: no token + simulation disabled must NOT grant free PRO.
                throw new IllegalStateException(
                        "Billing is not configured (MONOBANK_TOKEN missing) and dev-simulation is disabled");
            }
            // Dev: no merchant token — simulate a paid invoice so the flow works end-to-end.
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(Instant.now());
            extendPro(user);
            log.warn("[DEV] MONOBANK_TOKEN not set — simulated PRO purchase for {} (payment {})",
                    user.getEmail(), payment.getId());
            return new CheckoutResponse(props.returnUrl());
        }

        MonobankClient.InvoiceCreated invoice = monobankClient.createInvoice(
                toKopiykas(props.proPrice()), UAH, payment.getId().toString(),
                "Підписка Majstr PRO — " + props.proDays() + " днів");
        payment.setInvoiceId(invoice.invoiceId());
        log.info("Created monobank invoice {} for user {} (payment {})",
                invoice.invoiceId(), user.getEmail(), payment.getId());
        return new CheckoutResponse(invoice.pageUrl());
    }

    /**
     * Handles a monobank invoice-status webhook. Verifies the signature (prod),
     * finds the payment by invoice id, and on {@code success} — with a matching
     * amount — flips it to SUCCESS and extends PRO. Idempotent and fail-safe:
     * anything unverified/unknown/mismatched is logged and ignored, never granted.
     */
    @Transactional
    public void handleWebhook(byte[] body, String xSign) {
        if (!props.isConfigured()) {
            log.warn("Received billing webhook but MONOBANK_TOKEN not set — ignoring");
            return;
        }
        if (!signatureVerifier.verify(body, xSign, monobankClient.publicKey())) {
            log.warn("Rejected billing webhook with invalid signature");
            return;
        }

        Map<String, Object> payload = parse(body);
        if (payload == null) {
            return;
        }
        String invoiceId = str(payload.get("invoiceId"));
        String status = str(payload.get("status"));
        if (invoiceId == null || status == null) {
            log.warn("Billing webhook missing invoiceId/status");
            return;
        }

        Payment payment = paymentRepository.findByInvoiceId(invoiceId).orElse(null);
        if (payment == null) {
            log.warn("Billing webhook for unknown invoice {}", invoiceId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return; // idempotent — already granted
        }

        switch (status) {
            case "success" -> onSuccess(payment, payload);
            case "failure" -> mark(payment, PaymentStatus.FAILURE);
            case "expired" -> mark(payment, PaymentStatus.EXPIRED);
            case "reversed" -> mark(payment, PaymentStatus.REVERSED);
            case "processing", "hold" -> mark(payment, PaymentStatus.PROCESSING);
            default -> { /* created / unknown — leave PENDING */ }
        }
    }

    private void onSuccess(Payment payment, Map<String, Object> payload) {
        long expected = toKopiykas(payment.getAmount());
        Object amount = payload.get("amount");
        if (amount instanceof Number n && n.longValue() != expected) {
            log.error("Billing webhook amount {} != expected {} for invoice {} — not granting",
                    n.longValue(), expected, payment.getInvoiceId());
            return;
        }
        User user = userRepository.findById(payment.getUserId()).orElse(null);
        if (user == null) {
            log.error("Billing webhook: user {} for invoice {} no longer exists",
                    payment.getUserId(), payment.getInvoiceId());
            return;
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());
        extendPro(user);
        log.info("PRO granted to {} via invoice {} (until {})",
                user.getEmail(), payment.getInvoiceId(), user.getPlanExpiresAt());
    }

    /** Extends PRO by {@code proDays} — stacking onto a still-active subscription, or from now if lapsed/new. */
    private void extendPro(User user) {
        Instant now = Instant.now();
        Instant base = (user.getPlanExpiresAt() != null && user.getPlanExpiresAt().isAfter(now))
                ? user.getPlanExpiresAt()
                : now;
        user.setPlan(Plan.PRO);
        user.setPlanExpiresAt(base.plus(props.proDays(), ChronoUnit.DAYS));
        userRepository.save(user);
    }

    private void mark(Payment payment, PaymentStatus status) {
        payment.setStatus(status);
    }

    private Map<String, Object> parse(byte[] body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            return map;
        } catch (Exception e) {
            log.warn("Failed to parse billing webhook body: {}", e.getMessage());
            return null;
        }
    }

    private static long toKopiykas(BigDecimal uah) {
        return uah.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
