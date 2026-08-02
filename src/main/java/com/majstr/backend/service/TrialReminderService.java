package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tells a master his PRO trial is running out — once a day over its last three days.
 *
 * <p><b>Why daily, when the auto-renew reminder is a single mail.</b> They are different events. An
 * auto-renew is about to happen whether or not the master reads anything; a trial ending is him
 * silently losing features he has spent two weeks using, and the only way to keep them is an action
 * he has to take. One message three days out is easy to miss on a building site — and if it is
 * missed, the first he learns of it is a screen that stopped working.</p>
 *
 * <p><b>Both channels, on purpose.</b> A push is what actually gets noticed on a phone; an email is
 * what survives a master who never granted notification permission. Both are already fail-soft and
 * env-gated here, so a missing key degrades this to the other channel rather than breaking the
 * job.</p>
 *
 * <p>Runs on its own schedule rather than inside {@code AutoRenewService}: that job charges cards,
 * and an exception thrown while reminding trial users must not be able to stop money being
 * collected.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrialReminderService {

    private final BillingProperties props;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PushService pushService;

    @Scheduled(cron = "${app.billing.trial-reminder-cron:0 30 9 * * *}")
    @Transactional
    public void remindTrialsEnding() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(props.trialReminderDays(), ChronoUnit.DAYS);
        // Start of today in UTC. The object-economy month boundary has the same simplification and
        // it is logged as an open question; here it only decides WHICH DAY a reminder counts as,
        // so the worst case is a master hearing about it at an odd hour, never twice.
        Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);

        List<User> due = userRepository.findTrialEndingReminderDue(now, cutoff, startOfToday);
        for (User user : due) {
            long daysLeft = Math.max(1, ChronoUnit.DAYS.between(now, user.getPlanExpiresAt()) + 1);
            emailService.sendTrialEndingEmail(user, user.getPlanExpiresAt(), daysLeft);
            pushService.sendToUser(user,
                    "Пробний PRO закінчується",
                    daysLeft == 1
                            ? "Завтра останній день. Продовжте підписку, щоб не втратити функції PRO."
                            : "Лишилось днів: " + daysLeft + ". Продовжте підписку, щоб не втратити функції PRO.",
                    "/profile");
            // Stamped even if a channel was skipped: the point of this field is "we already spoke
            // to him today", and re-running the job must not turn one silent channel into two
            // messages on the one that works.
            user.setTrialReminderSentAt(now);
        }
        if (!due.isEmpty()) {
            log.info("Trial reminders: notified {} master(s) whose trial ends within {} day(s)",
                    due.size(), props.trialReminderDays());
        }
    }
}
