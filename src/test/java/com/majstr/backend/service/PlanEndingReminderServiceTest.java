package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** «PRO закінчується» (V200): said three days out and again one day out — never twice per stage. */
@ExtendWith(MockitoExtension.class)
class PlanEndingReminderServiceTest {

    @Mock UserRepository userRepository;
    @Mock EmailService emailService;
    @Mock PushService pushService;
    @InjectMocks PlanEndingReminderService service;

    private static final Instant NOW = Instant.parse("2026-11-11T09:35:00Z");

    @Test
    void theFirstStageIsDueOnceThreeDaysOut() {
        Instant expires = NOW.plus(Duration.ofDays(2));
        assertThat(PlanEndingReminderService.isDue(null, expires, NOW)).isTrue();
        assertThat(PlanEndingReminderService.isDue(NOW.minus(Duration.ofHours(20)), expires, NOW)).isFalse();
    }

    @Test
    void theLastDayIsSaidAgainEvenAfterTheFirstStage() {
        Instant expires = NOW.plus(Duration.ofHours(20));
        Instant saidThreeDaysOut = expires.minus(Duration.ofDays(2));
        assertThat(PlanEndingReminderService.isDue(saidThreeDaysOut, expires, NOW)).isTrue();
        assertThat(PlanEndingReminderService.isDue(NOW.minus(Duration.ofHours(1)), expires, NOW)).isFalse();
    }

    @Test
    void aStampFromAnEarlierTermDoesNotSilenceTheNewOne() {
        Instant expires = NOW.plus(Duration.ofDays(2));
        assertThat(PlanEndingReminderService.isDue(NOW.minus(Duration.ofDays(40)), expires, NOW)).isTrue();
    }

    @Test
    void sendsBothChannelsAndStampsTheStage() {
        User u = User.builder().id(UUID.randomUUID()).email("m@majstr.test").plan(Plan.PRO)
                .planExpiresAt(Instant.now().plus(Duration.ofHours(30))).build();
        given(userRepository.findPlanEndingCandidates(any(), any())).willReturn(List.of(u));

        service.remindPlansEnding();

        verify(emailService).sendPlanEndingEmail(eq(u), eq(u.getPlanExpiresAt()), eq(2L));
        verify(pushService).sendToUser(eq(u), eq("PRO закінчується через 2 дн."), anyString(), eq("/profile"));
        assertThat(u.getPlanEndingRemindedAt()).isNotNull();
    }

    @Test
    void anAlreadySaidStageSendsNothing() {
        User u = User.builder().id(UUID.randomUUID()).email("m@majstr.test").plan(Plan.PRO)
                .planExpiresAt(Instant.now().plus(Duration.ofHours(60)))
                .planEndingRemindedAt(Instant.now().minus(Duration.ofHours(2))).build();
        given(userRepository.findPlanEndingCandidates(any(), any())).willReturn(List.of(u));

        service.remindPlansEnding();

        verify(emailService, never()).sendPlanEndingEmail(any(), any(), anyLong());
        verify(pushService, never()).sendToUser(any(), anyString(), anyString(), anyString());
    }
}
