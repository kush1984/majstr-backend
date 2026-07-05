package com.majstr.backend.email;

import com.majstr.backend.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transactional-email transport. One implementation today
 * ({@link ResendEmailService}); the interface keeps the provider swappable.
 * All methods are fail-soft — a send failure is logged, never propagated.
 */
public interface EmailService {

    /** Send the "confirm your email" message. Implementations must not throw — a failure is logged, not propagated. */
    void sendVerificationEmail(User user, String token);

    /** Send a client the portal link to their estimate. Must not throw — a failure is logged, not propagated. */
    void sendEstimateShareEmail(String toEmail, String clientName, String contractorName, String projectName, String shareUrl);

    /** Auto-renew T-3 warning: "we'll charge {amount} on {chargeDate}; disable in your profile". */
    void sendRenewReminderEmail(User user, Instant chargeDate, BigDecimal amount);

    /** Auto-renew receipt: "payment received, PRO active until {until}". */
    void sendRenewReceiptEmail(User user, Instant until, BigDecimal amount);

    /** Auto-renew failed: "couldn't charge your card; access kept until {accessUntil}; update card". */
    void sendRenewFailedEmail(User user, Instant accessUntil, BigDecimal amount);
}
