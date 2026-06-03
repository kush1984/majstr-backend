package com.majstr.backend.email;

import com.majstr.backend.entity.User;

/**
 * Transactional-email transport. One implementation today
 * ({@link ResendEmailService}); the interface keeps the provider swappable.
 */
public interface EmailService {

    /** Send the "confirm your email" message. Implementations must not throw — a failure is logged, not propagated. */
    void sendVerificationEmail(User user, String token);

    /** Send a client the portal link to their estimate. Must not throw — a failure is logged, not propagated. */
    void sendEstimateShareEmail(String toEmail, String clientName, String contractorName, String projectName, String shareUrl);
}
