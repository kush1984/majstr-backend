package com.majstr.backend.service;

import com.majstr.backend.billing.MonobankClient;
import com.majstr.backend.billing.MonobankSignatureVerifier;
import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.BillingPeriod;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentKind;
import com.majstr.backend.entity.PaymentProvider;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.ReferralReward;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.TrialNotAvailableException;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.ReferralRewardRepository;
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
    private final ReferralRewardRepository referralRewardRepository;
    private final ObjectMapper objectMapper;
    private final EmailService emailService;

    @Transactional
    public CheckoutResponse checkout(UUID userId, boolean autoRenew, BillingPeriod period) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // No PRO without a verified email — same rule as the trial. Keeps every PRO
        // account reachable (receipts, renewal reminders, recovery) and the policy
        // consistent: a throwaway inbox can't buy its way past verification either.
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }

        // Amount + day count are derived server-side from the period — the client
        // sends only the period, never a price it could tamper with.
        BigDecimal price = props.priceFor(period);
        int days = props.daysFor(period);
        Payment payment = paymentRepository.save(Payment.builder()
                .userId(userId)
                .provider(PaymentProvider.MONOBANK)
                .amount(price)
                .ccy(UAH)
                .status(PaymentStatus.PENDING)
                .plan(Plan.PRO)
                .days(days)
                .period(period)
                .kind(PaymentKind.CHECKOUT)
                .build());

        // Opt-in tokenization: a fresh wallet id marks this checkout as "save the
        // card"; the success webhook then fetches + stores the token (see onSuccess).
        // Cleared when not opting in, so a plain checkout never triggers a fetch.
        // renewPeriod is stored so auto-renew later recharges the SAME period.
        String walletId = autoRenew ? UUID.randomUUID().toString() : null;
        user.setWalletId(walletId);
        if (autoRenew) {
            user.setRenewPeriod(period);
        }

        if (!props.isConfigured()) {
            if (!props.allowDevSimulation()) {
                // Prod safety: no token + simulation disabled must NOT grant free PRO.
                throw new IllegalStateException(
                        "Billing is not configured (MONOBANK_TOKEN missing) and dev-simulation is disabled");
            }
            // Dev: simulate a paid invoice (and, if opted in, a tokenized card) so
            // the whole auto-renew flow is testable without a merchant account.
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(Instant.now());
            if (autoRenew) {
                user.setCardToken("DEV-TOKEN-" + walletId);
                user.setCardMask("424242****4242");
                user.setAutoRenew(true);
            }
            extendPro(user, days);
            grantReferralReward(user, payment);
            log.warn("[DEV] MONOBANK_TOKEN not set — simulated PRO purchase for {} (period={}, autoRenew={})",
                    user.getEmail(), period, autoRenew);
            return new CheckoutResponse(props.returnUrl());
        }

        MonobankClient.InvoiceCreated invoice = monobankClient.createInvoice(
                toKopiykas(price), UAH, payment.getId().toString(),
                "Підписка Majstr PRO — " + days + " днів", walletId);
        payment.setInvoiceId(invoice.invoiceId());
        log.info("Created monobank invoice {} for user {} (payment {}, period {}, autoRenew={})",
                invoice.invoiceId(), user.getEmail(), payment.getId(), period, autoRenew);
        return new CheckoutResponse(invoice.pageUrl());
    }

    /**
     * Activates the one-time self-serve PRO trial: grants {@code trialDays} of PRO
     * with no payment and no card. Allowed only for a master currently on FREE who
     * has never taken the trial ({@code trialStartedAt == null}); otherwise 409
     * {@code TRIAL_UNAVAILABLE}. The daily {@link BillingExpiryService} downgrades it
     * back to FREE after it lapses (the master keeps everything they created), and
     * {@code trialStartedAt} stays set so it can't be claimed twice.
     */
    @Transactional
    public User startTrial(UUID userId) {
        // Fetch trades in-session — the controller maps UserResponse after the tx
        // commits (open-in-view off), so the LAZY collection must be initialized here.
        User user = userRepository.findWithTradesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        // A trial grants PRO (incl. paid AI features that cost real API money) — so
        // require a verified email, else a throwaway address can farm free trials.
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
        if (user.getTrialStartedAt() != null) {
            throw new TrialNotAvailableException("Trial already used by " + user.getEmail());
        }
        if (user.getPlan() != Plan.FREE) {
            throw new TrialNotAvailableException("Trial available only on FREE (current: " + user.getPlan() + ")");
        }
        Instant now = Instant.now();
        user.setTrialStartedAt(now);
        user.setPlan(Plan.PRO);
        user.setPlanExpiresAt(now.plus(props.trialDays(), ChronoUnit.DAYS));
        log.info("PRO trial started for {} ({} days, until {})", user.getEmail(), props.trialDays(), user.getPlanExpiresAt());
        return user;
    }

    /**
     * Merchant-initiated auto-renew charge of the user's saved token — called by the
     * scheduled {@code AutoRenewService} for a due subscription. Creates an
     * AUTO_RENEW payment; the result arrives via the same webhook (which extends PRO
     * + emails a receipt). Caller guarantees the user has a token and is due.
     */
    @Transactional
    public void chargeAutoRenew(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Recharge the SAME period the master bought (default MONTH for legacy
        // subscriptions tokenized before periods existed).
        BillingPeriod period = user.getRenewPeriod() != null ? user.getRenewPeriod() : BillingPeriod.MONTH;
        BigDecimal price = props.priceFor(period);
        int days = props.daysFor(period);
        Payment payment = paymentRepository.save(Payment.builder()
                .userId(userId)
                .provider(PaymentProvider.MONOBANK)
                .amount(price)
                .ccy(UAH)
                .status(PaymentStatus.PENDING)
                .plan(Plan.PRO)
                .days(days)
                .period(period)
                .kind(PaymentKind.AUTO_RENEW)
                .build());

        if (!props.isConfigured()) {
            if (!props.allowDevSimulation()) {
                log.warn("Auto-renew charge skipped for {} — billing not configured", user.getEmail());
                payment.setStatus(PaymentStatus.FAILURE);
                return;
            }
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(Instant.now());
            extendPro(user, days);
            emailService.sendRenewReceiptEmail(user, user.getPlanExpiresAt(), price);
            log.warn("[DEV] simulated auto-renew charge for {} (period {}, until {})",
                    user.getEmail(), period, user.getPlanExpiresAt());
            return;
        }

        String invoiceId = monobankClient.tokenPayment(
                user.getCardToken(), toKopiykas(price), UAH,
                payment.getId().toString(), "Автопродовження Majstr PRO — " + days + " днів");
        payment.setInvoiceId(invoiceId);
        log.info("Auto-renew charge started for {} (invoice {}, period {})", user.getEmail(), invoiceId, period);
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
        extendPro(user, payment.getDays());
        grantReferralReward(user, payment);

        // A tokenizing checkout just succeeded → fetch + store the saved card and
        // turn auto-renew on. Only fires when this checkout opted in (walletId set,
        // no token yet) — never for an auto-renew charge (token already present).
        if (user.getWalletId() != null && user.getCardToken() == null) {
            try {
                MonobankClient.WalletCard card = monobankClient.firstWalletCard(user.getWalletId());
                if (card != null) {
                    user.setCardToken(card.cardToken());
                    user.setCardMask(card.maskedPan());
                    user.setAutoRenew(true);
                    log.info("Auto-renew enabled for {} (card {})", user.getEmail(), card.maskedPan());
                }
            } catch (Exception e) {
                // Non-fatal: the PRO grant stands; the master can retry enabling auto-renew.
                log.error("Failed to fetch tokenized card for {}: {}", user.getEmail(), e.getMessage());
            }
        }

        if (payment.getKind() == PaymentKind.AUTO_RENEW) {
            emailService.sendRenewReceiptEmail(user, user.getPlanExpiresAt(), payment.getAmount());
        }
        log.info("PRO granted to {} via invoice {} (until {}, kind {})",
                user.getEmail(), payment.getInvoiceId(), user.getPlanExpiresAt(), payment.getKind());
    }

    /** Extends PRO by {@code days} — stacking onto a still-active subscription, or
     *  from now if lapsed/new. Clears the T-3 reminder flag so the next cycle gets one. */
    private void extendPro(User user, int days) {
        Instant now = Instant.now();
        Instant base = (user.getPlanExpiresAt() != null && user.getPlanExpiresAt().isAfter(now))
                ? user.getPlanExpiresAt()
                : now;
        user.setPlan(Plan.PRO);
        user.setPlanExpiresAt(base.plus(days, ChronoUnit.DAYS));
        user.setRenewReminderSentAt(null); // new cycle → allow one reminder
        userRepository.save(user);
    }

    /**
     * Master→master reward: when {@code payer} makes their <b>first</b> successful
     * payment and was invited by another master, the referrer earns
     * {@code referralRewardDays} of PRO. One-time per invited master, guaranteed by
     * {@code referral_rewards.referred_user_id} UNIQUE (a retried webhook or a second
     * payment can't double-grant — the existence check short-circuits, the constraint
     * is the hard backstop). A referrer already on an admin-granted <b>dateless</b> PRO
     * has nothing to extend, so the reward is recorded with 0 days (audit only).
     */
    private void grantReferralReward(User payer, Payment payment) {
        UUID referrerId = payer.getReferredByUserId();
        if (referrerId == null || referralRewardRepository.existsByReferredUserId(payer.getId())) {
            return;
        }
        User referrer = userRepository.findById(referrerId).orElse(null);
        if (referrer == null) {
            return;
        }
        boolean datelessPro = referrer.getPlan() == Plan.PRO && referrer.getPlanExpiresAt() == null;
        int grantedDays = datelessPro ? 0 : props.referralRewardDays();
        if (!datelessPro) {
            extendPro(referrer, grantedDays);
        }
        referralRewardRepository.save(ReferralReward.builder()
                .referrerId(referrerId)
                .referredUserId(payer.getId())
                .paymentId(payment.getId())
                .grantedDays(grantedDays)
                .build());
        log.info("Referral reward: {} PRO day(s) to referrer {} for {}'s first payment (payment {})",
                grantedDays, referrerId, payer.getEmail(), payment.getId());
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
