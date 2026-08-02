package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The trial-ending reminder.
 *
 * <p>What is worth testing here is not that a message goes out — it is the shape of the message and
 * the fact that it is stamped, because the request was «кожного дня до закінчення» and the two ways
 * to get that wrong are saying nothing and saying it twice.</p>
 */
@ExtendWith(MockitoExtension.class)
class TrialReminderServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private PushService pushService;

    private TrialReminderService service() {
        BillingProperties props = new BillingProperties("tok", "https://api.monobank.ua",
                new BigDecimal("299"), 30, new BigDecimal("1494"), new BigDecimal("2748"), 3,
                "http://ret", "http://hook", true, 3, 30, 15, 3);
        return new TrialReminderService(props, userRepository, emailService, pushService);
    }

    private static User trialEndingIn(long days) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("m@test.ua")
                .plan(Plan.PRO)
                .trialStartedAt(Instant.now().minus(15 - days, ChronoUnit.DAYS))
                .planExpiresAt(Instant.now().plus(days, ChronoUnit.DAYS))
                .build();
    }

    @Test
    void notifiesOnBOTHchannels_becauseEitherOneAloneMissesSomebody() {
        // Push is what gets noticed on a phone; email is what reaches a master who never granted
        // notification permission. Both are fail-soft, so neither being configured is not an error.
        User master = trialEndingIn(2);
        given(userRepository.findTrialEndingReminderDue(any(), any(), any())).willReturn(List.of(master));

        service().remindTrialsEnding();

        verify(emailService).sendTrialEndingEmail(any(User.class), any(Instant.class), anyLong());
        verify(pushService).sendToUser(any(User.class), anyString(), anyString(), anyString());
    }

    @Test
    void stampsTheUserSoARERUNonTheSAMEDAYcannotSpeakTwice() {
        // The query filters on this field. Without the stamp a restarted job — or a second cron
        // firing — would notify the same master again on the same day, which is how a helpful
        // reminder turns into the reason he mutes notifications.
        User master = trialEndingIn(3);
        given(userRepository.findTrialEndingReminderDue(any(), any(), any())).willReturn(List.of(master));
        assertThat(master.getTrialReminderSentAt()).isNull();

        service().remindTrialsEnding();

        assertThat(master.getTrialReminderSentAt()).isNotNull();
    }

    @Test
    void saysTOMORROWonTheLASTday_notZeroDaysLeft() {
        // «Лишилось днів: 0» reads as "already over" and is the one wording that would make a master
        // stop trying.
        User master = trialEndingIn(0);
        given(userRepository.findTrialEndingReminderDue(any(), any(), any())).willReturn(List.of(master));

        service().remindTrialsEnding();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushService).sendToUser(any(User.class), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("Завтра").doesNotContain("0");
    }

    @Test
    void doesNothingWhenNobodyIsDue() {
        given(userRepository.findTrialEndingReminderDue(any(), any(), any())).willReturn(List.of());

        service().remindTrialsEnding();

        verify(emailService, never()).sendTrialEndingEmail(any(), any(), anyLong());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), anyString());
    }
}
