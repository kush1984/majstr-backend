package com.majstr.backend.service;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Settles the payments no webhook closed. monobank delivers a webhook at most three times and never
 * for an expired invoice, so without this a lost delivery is money taken and PRO never granted, with
 * nothing left to notice it.
 *
 * <p>An auto-renew row with no invoice id is the one case that cannot be asked about: the charge
 * timed out, so the status endpoint has nothing to look up. It may or may not have gone through, so
 * it is never closed by guessing. The row keeps blocking another charge, its webhook still finds it
 * by reference if the charge did happen, and a person is told once.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReconcileJob {

    /** Long enough that this is not racing an ordinary webhook. */
    static final Duration SETTLE_AFTER = Duration.ofMinutes(20);
    static final Duration GIVE_UP_AFTER = Duration.ofDays(30);
    /** One run of this hourly job lands in this window exactly once per row. */
    static final Duration REPORT_AFTER = Duration.ofHours(1);
    private static final int BATCH_SIZE = 100;

    private final BillingProperties props;
    private final BillingService billingService;
    private final PaymentRepository paymentRepository;

    @Scheduled(cron = "${app.billing.reconcile-cron:0 20 * * * *}")
    public void reconcile() {
        if (!props.isConfigured()) {
            return;
        }
        Instant now = Instant.now();
        List<Payment> unsettled = paymentRepository.findUnsettled(
                now.minus(SETTLE_AFTER), now.minus(GIVE_UP_AFTER), PageRequest.of(0, BATCH_SIZE));
        for (Payment payment : unsettled) {
            try {
                if (payment.getInvoiceId() != null) {
                    billingService.reconcile(payment);
                } else {
                    reportOnce(payment, now);
                }
            } catch (HttpClientErrorException e) {
                // monobank does not know this invoice; asking again next hour changes nothing worth a page.
                log.warn("Reconcile of payment {}: monobank answered {}", payment.getId(), e.getStatusCode());
            } catch (RuntimeException e) {
                log.error("Reconcile of payment {} failed: {}", payment.getId(), e.getMessage());
                BackgroundErrors.capture("billing-reconcile", e);
            }
        }
    }

    private void reportOnce(Payment payment, Instant now) {
        Duration age = Duration.between(payment.getCreatedAt(), now);
        if (age.compareTo(REPORT_AFTER) >= 0 && age.compareTo(REPORT_AFTER.plusHours(1)) < 0) {
            BackgroundErrors.capture("billing-unknown-charge", new IllegalStateException(
                    "Auto-renew payment " + payment.getId() + " has no invoice id: the charge may have gone"
                            + " through. Find it in the monobank statement by that reference."));
        }
    }
}
