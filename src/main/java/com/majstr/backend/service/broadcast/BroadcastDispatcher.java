package com.majstr.backend.service.broadcast;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.config.BroadcastProperties;
import com.majstr.backend.config.EmailProperties;
import com.majstr.backend.config.PortalProperties;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Broadcast;
import com.majstr.backend.entity.BroadcastRecipient;
import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.BroadcastRecipientRepository;
import com.majstr.backend.repository.BroadcastRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends a broadcast's PENDING letters one by one, off the request thread.
 *
 * <p>Each letter's outcome is saved the moment it is known (no surrounding transaction), so a
 * restart mid-send leaves exactly the unsent rows PENDING and {@link BroadcastService} resumes from
 * them. The worst a crash can do is repeat the ONE letter that was in the air.</p>
 *
 * <p>Two things are re-checked per letter, because minutes can pass between pressing «Надіслати»
 * and the letter's turn: an unsubscribe in the meantime, and — for the verify-your-email segment —
 * a master who verified in the meantime. Both become SKIPPED, never a letter.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastDispatcher {

    public static final String UNSUBSCRIBE_PATH = "/api/public/unsubscribe/";

    private final BroadcastRepository broadcastRepository;
    private final BroadcastRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final BroadcastProperties broadcastProperties;
    private final EmailProperties emailProperties;
    private final PortalProperties portalProperties;

    @Async
    public void dispatch(UUID broadcastId) {
        try {
            run(broadcastId);
        } catch (RuntimeException e) {
            log.error("Broadcast {} stopped: {}", broadcastId, e.getMessage());
            BackgroundErrors.capture("broadcast", e);
        }
    }

    void run(UUID broadcastId) {
        Broadcast broadcast = broadcastRepository.findById(broadcastId).orElse(null);
        if (broadcast == null || broadcast.getFinishedAt() != null) {
            return;
        }
        int sent = 0;
        for (BroadcastRecipient r : recipientRepository.findByBroadcastIdAndStatus(
                broadcastId, BroadcastRecipient.Status.PENDING)) {
            if (sent > 0) {
                pause();
            }
            if (deliver(broadcast, r)) {
                sent++;
            }
        }
        broadcast.setFinishedAt(Instant.now());
        broadcastRepository.save(broadcast);
        log.info("Broadcast {} ({}) finished: {} letter(s) sent", broadcastId, broadcast.getSegment(), sent);
    }

    private boolean deliver(Broadcast broadcast, BroadcastRecipient r) {
        Optional<User> user = userRepository.findById(r.getUserId());
        String skip = user.isEmpty() ? "user deleted"
                : user.get().getBroadcastOptOutAt() != null ? "unsubscribed"
                : broadcast.getSegment() == BroadcastSegment.UNVERIFIED && user.get().isEmailVerified()
                        ? "email already verified" : null;
        if (skip != null) {
            finish(r, BroadcastRecipient.Status.SKIPPED, skip);
            return false;
        }
        try {
            BroadcastLetter.Rendered letter = BroadcastLetter.render(
                    broadcast.getSubject(), broadcast.getBody(), user.get().getFullName(),
                    broadcast.getSegment().buttonLabel(), buttonUrl(broadcast.getSegment(), r.getUserId()),
                    unsubscribeUrl(r.getUnsubscribeToken()));
            emailService.sendBroadcast(new EmailService.BroadcastEmail(
                    r.getEmail(), broadcastProperties.fromName(), broadcastProperties.replyTo(),
                    letter.subject(), letter.html(), letter.text(), unsubscribeUrl(r.getUnsubscribeToken())));
            finish(r, BroadcastRecipient.Status.SENT, null);
            return true;
        } catch (RuntimeException e) {
            log.warn("Broadcast letter to {} failed: {}", r.getEmail(), e.getMessage());
            finish(r, BroadcastRecipient.Status.FAILED, e.getMessage());
            return false;
        }
    }

    String buttonUrl(BroadcastSegment segment, UUID userId) {
        return segment == BroadcastSegment.UNVERIFIED
                ? emailVerificationService.verifyLinkFor(userId)
                : emailProperties.appUrl() + "/";
    }

    String unsubscribeUrl(String token) {
        return portalProperties.publicBaseUrl() + UNSUBSCRIBE_PATH + token;
    }

    private void finish(BroadcastRecipient r, BroadcastRecipient.Status status, String error) {
        r.setStatus(status);
        r.setError(error == null ? null : error.length() > 500 ? error.substring(0, 500) : error);
        r.setSentAt(status == BroadcastRecipient.Status.SENT ? Instant.now() : null);
        recipientRepository.save(r);
    }

    private void pause() {
        if (broadcastProperties.sendIntervalMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(broadcastProperties.sendIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
