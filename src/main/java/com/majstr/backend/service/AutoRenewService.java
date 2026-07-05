package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.BillingPeriod;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.User;

import java.math.BigDecimal;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled subscription auto-renewal — runs daily BEFORE the downgrade job
 * ({@link BillingExpiryService}, cron {@code 0 30 3}). Two passes:
 *
 * <ol>
 *   <li><b>Reminders (T-N):</b> one warning email per cycle for opted-in, tokenized
 *       subscriptions expiring soon (dedup via {@code renewReminderSentAt}).</li>
 *   <li><b>Charge:</b> for a subscription that just expired but is still in grace,
 *       charge the saved token once per day. One in-flight attempt at a time
 *       (skip while a PENDING attempt awaits its webhook); a "update card" email
 *       after the first failure; when grace runs out the downgrade job takes over
 *       (and clears auto-renew) — a dead card is never hammered.</li>
 * </ol>
 *
 * <p>No class-level transaction: each charge runs in {@code BillingService.chargeAutoRenew}'s
 * own transaction, so one user's failure never rolls back the others.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRenewService {

    private final BillingProperties props;
    private final BillingService billingService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;

    @Scheduled(cron = "${app.billing.auto-renew-cron:0 0 3 * * *}")
    public void runAutoRenew() {
        sendReminders();
        chargeDue();
    }

    /** Pass 1 — one T-N warning email per cycle. */
    void sendReminders() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(props.renewReminderDays(), ChronoUnit.DAYS);
        List<User> due = userRepository.findAutoRenewReminderDue(now, cutoff);
        for (User user : due) {
            emailService.sendRenewReminderEmail(user, user.getPlanExpiresAt(), renewPrice(user));
            user.setRenewReminderSentAt(now);
            userRepository.save(user);
        }
        if (!due.isEmpty()) {
            log.info("Auto-renew: sent {} T-{} reminder(s)", due.size(), props.renewReminderDays());
        }
    }

    /** Pass 2 — charge due subscriptions (expired but in grace), one attempt/day. */
    void chargeDue() {
        Instant now = Instant.now();
        Instant graceStart = now.minus(props.graceDays(), ChronoUnit.DAYS);
        Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);
        for (User user : userRepository.findAutoRenewChargeDue(now, graceStart)) {
            List<Payment> attempts = paymentRepository.findAutoRenewSince(user.getId(), graceStart);
            boolean inFlight = attempts.stream()
                    .anyMatch(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.PROCESSING);
            if (inFlight) {
                continue; // a charge is awaiting its webhook — don't double-charge
            }
            boolean triedToday = attempts.stream().anyMatch(p -> !p.getCreatedAt().isBefore(startOfToday));
            if (triedToday) {
                continue; // one attempt per day
            }
            long failures = attempts.stream().filter(p -> isFailed(p.getStatus())).count();
            if (failures == 1) {
                // Exactly after the first failure resolved → ask them to update the card (once).
                emailService.sendRenewFailedEmail(user,
                        user.getPlanExpiresAt().plus(props.graceDays(), ChronoUnit.DAYS), renewPrice(user));
            }
            try {
                billingService.chargeAutoRenew(user.getId());
            } catch (Exception e) {
                log.error("Auto-renew charge failed to start for {}: {}", user.getEmail(), e.getMessage());
            }
        }
    }

    private static boolean isFailed(PaymentStatus s) {
        return s == PaymentStatus.FAILURE || s == PaymentStatus.EXPIRED || s == PaymentStatus.REVERSED;
    }

    /** The amount auto-renew will charge this user — their stored period's price
     *  (MONTH for legacy subscriptions tokenized before periods existed). */
    private BigDecimal renewPrice(User user) {
        BillingPeriod period = user.getRenewPeriod() != null ? user.getRenewPeriod() : BillingPeriod.MONTH;
        return props.priceFor(period);
    }
}
