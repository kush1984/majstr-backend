package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * «PRO закінчується — далі буде FREE» for every paid term that is about to END rather than renew:
 * a subscription without auto-renew and a PRO an admin granted for a term (V199). A trial keeps
 * {@link TrialReminderService} and its own words; a renewable subscription gets the auto-renew
 * letter instead. Push and email, three days out and again one day out.
 *
 * <p>Before this, only the trial was ever warned: everyone else learned their PRO was gone from a
 * feature that stopped working.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanEndingReminderService {

    static final Duration FIRST = Duration.ofDays(3);
    static final Duration LAST = Duration.ofDays(1);

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PushService pushService;

    @Scheduled(cron = "${app.billing.plan-ending-reminder-cron:0 35 9 * * *}")
    @Transactional
    public void remindPlansEnding() {
        Instant now = Instant.now();
        List<User> candidates = userRepository.findPlanEndingCandidates(now, now.plus(FIRST));
        int sent = 0;
        for (User user : candidates) {
            if (!isDue(user.getPlanEndingRemindedAt(), user.getPlanExpiresAt(), now)) {
                continue;
            }
            long daysLeft = Math.max(1, ChronoUnit.DAYS.between(now, user.getPlanExpiresAt()) + 1);
            emailService.sendPlanEndingEmail(user, user.getPlanExpiresAt(), daysLeft);
            pushService.sendToUser(user,
                    daysLeft == 1 ? "PRO закінчується завтра" : "PRO закінчується через " + daysLeft + " дн.",
                    "Далі буде FREE: частина функцій вимкнеться, дані лишаться. Продовжте PRO в профілі.",
                    "/profile");
            // Stamped even when a channel was skipped (no VAPID key, no email key): this field
            // means «this stage was said», and a rerun must not say it again on the channel that works.
            user.setPlanEndingRemindedAt(now);
            sent++;
        }
        if (sent > 0) {
            log.info("Plan-ending reminders: notified {} master(s)", sent);
        }
    }

    /**
     * Due when the latest stage that has begun — three days out, then one day out — has not been
     * said yet. A stamp from an EARLIER term is older than this term's first stage, so a renewed or
     * re-granted plan is warned afresh.
     */
    static boolean isDue(Instant remindedAt, Instant expiresAt, Instant now) {
        Instant last = expiresAt.minus(LAST);
        Instant stage = now.isBefore(last) ? expiresAt.minus(FIRST) : last;
        return remindedAt == null || remindedAt.isBefore(stage);
    }
}
