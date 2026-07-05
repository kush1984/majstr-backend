package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AutoRenewServiceTest {

    @Mock BillingService billingService;
    @Mock EmailService emailService;
    @Mock UserRepository userRepository;
    @Mock PaymentRepository paymentRepository;

    private AutoRenewService service() {
        BillingProperties props = new BillingProperties("tok", "https://api.monobank.ua",
                new BigDecimal("299"), 30, 3, "http://ret", "http://hook", true, 3);
        return new AutoRenewService(props, billingService, emailService, userRepository, paymentRepository);
    }

    private User due(UUID id, Instant expiresAt) {
        return User.builder().id(id).email("m@x").autoRenew(true).cardToken("tok").cardMask("mask")
                .planExpiresAt(expiresAt).build();
    }

    private Payment attempt(PaymentStatus status, Instant createdAt) {
        return Payment.builder().id(UUID.randomUUID()).status(status).createdAt(createdAt).build();
    }

    @Test
    void sendReminders_sendsOneReminderAndMarksIt() {
        UUID id = UUID.randomUUID();
        User u = due(id, Instant.now().plus(2, ChronoUnit.DAYS));
        given(userRepository.findAutoRenewReminderDue(any(), any())).willReturn(List.of(u));

        service().sendReminders();

        verify(emailService).sendRenewReminderEmail(eq(u), any(), any());
        assertThat(u.getRenewReminderSentAt()).isNotNull();
        verify(userRepository).save(u);
    }

    @Test
    void chargeDue_firstAttempt_chargesWithoutFailureEmail() {
        UUID id = UUID.randomUUID();
        given(userRepository.findAutoRenewChargeDue(any(), any()))
                .willReturn(List.of(due(id, Instant.now().minus(1, ChronoUnit.HOURS))));
        given(paymentRepository.findAutoRenewSince(eq(id), any())).willReturn(List.of());

        service().chargeDue();

        verify(billingService).chargeAutoRenew(id);
        verify(emailService, never()).sendRenewFailedEmail(any(), any(), any());
    }

    @Test
    void chargeDue_inFlightPending_skips() {
        UUID id = UUID.randomUUID();
        given(userRepository.findAutoRenewChargeDue(any(), any()))
                .willReturn(List.of(due(id, Instant.now().minus(1, ChronoUnit.HOURS))));
        given(paymentRepository.findAutoRenewSince(eq(id), any()))
                .willReturn(List.of(attempt(PaymentStatus.PENDING, Instant.now().minus(1, ChronoUnit.DAYS))));

        service().chargeDue();

        verify(billingService, never()).chargeAutoRenew(any());
    }

    @Test
    void chargeDue_alreadyTriedToday_skips() {
        UUID id = UUID.randomUUID();
        given(userRepository.findAutoRenewChargeDue(any(), any()))
                .willReturn(List.of(due(id, Instant.now().minus(1, ChronoUnit.HOURS))));
        given(paymentRepository.findAutoRenewSince(eq(id), any()))
                .willReturn(List.of(attempt(PaymentStatus.FAILURE, Instant.now())));

        service().chargeDue();

        verify(billingService, never()).chargeAutoRenew(any());
    }

    @Test
    void chargeDue_afterFirstFailure_emailsThenRetries() {
        UUID id = UUID.randomUUID();
        given(userRepository.findAutoRenewChargeDue(any(), any()))
                .willReturn(List.of(due(id, Instant.now().minus(1, ChronoUnit.HOURS))));
        // One failure from yesterday (not today) → the retry proceeds + one email.
        given(paymentRepository.findAutoRenewSince(eq(id), any()))
                .willReturn(List.of(attempt(PaymentStatus.FAILURE, Instant.now().minus(1, ChronoUnit.DAYS))));

        service().chargeDue();

        verify(emailService).sendRenewFailedEmail(any(), any(), any());
        verify(billingService).chargeAutoRenew(id);
    }
}
