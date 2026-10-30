package com.majstr.backend.service.broadcast;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastDispatcherTest {

    @Mock BroadcastRepository broadcastRepository;
    @Mock BroadcastRecipientRepository recipientRepository;
    @Mock UserRepository userRepository;
    @Mock EmailService emailService;
    @Mock EmailVerificationService emailVerificationService;

    BroadcastDispatcher dispatcher;
    UUID broadcastId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new BroadcastDispatcher(broadcastRepository, recipientRepository, userRepository,
                emailService, emailVerificationService,
                new BroadcastProperties("Андрій з Майстра", "majstr.app@ukr.net", 0),
                new EmailProperties("key", "Majstr <hi@majstr.test>", "https://app.majstr.test"),
                new PortalProperties("https://api.majstr.test"));
    }

    @Test
    void aLetterGoesOutPersonalized_andTheRowIsMarkedSent() {
        Broadcast b = broadcast(BroadcastSegment.INACTIVE);
        User u = user(true, null);
        BroadcastRecipient r = pending(u);
        given(recipientRepository.findByBroadcastIdAndStatus(broadcastId, BroadcastRecipient.Status.PENDING))
                .willReturn(List.of(r));
        given(userRepository.findById(u.getId())).willReturn(Optional.of(u));

        dispatcher.run(broadcastId);

        ArgumentCaptor<EmailService.BroadcastEmail> mail = ArgumentCaptor.forClass(EmailService.BroadcastEmail.class);
        verify(emailService).sendBroadcast(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("p@t.ua");
        assertThat(mail.getValue().replyTo()).isEqualTo("majstr.app@ukr.net");
        assertThat(mail.getValue().subject()).isEqualTo("Петро, привіт");
        assertThat(mail.getValue().unsubscribeUrl()).isEqualTo("https://api.majstr.test/api/public/unsubscribe/tok");
        assertThat(mail.getValue().html()).contains("https://app.majstr.test/");
        assertThat(r.getStatus()).isEqualTo(BroadcastRecipient.Status.SENT);
        assertThat(r.getSentAt()).isNotNull();
        assertThat(b.getFinishedAt()).isNotNull();
    }

    @Test
    void somebodyWhoUnsubscribedMeanwhileIsSkipped() {
        broadcast(BroadcastSegment.INACTIVE);
        User u = user(true, Instant.now());
        BroadcastRecipient r = pending(u);
        given(recipientRepository.findByBroadcastIdAndStatus(broadcastId, BroadcastRecipient.Status.PENDING))
                .willReturn(List.of(r));
        given(userRepository.findById(u.getId())).willReturn(Optional.of(u));

        dispatcher.run(broadcastId);

        verify(emailService, never()).sendBroadcast(any());
        assertThat(r.getStatus()).isEqualTo(BroadcastRecipient.Status.SKIPPED);
        assertThat(r.getError()).isEqualTo("unsubscribed");
    }

    @Test
    void theVerifySegmentSkipsAMasterWhoVerifiedMeanwhile_andMintsALinkForTheRest() {
        broadcast(BroadcastSegment.UNVERIFIED);
        User verified = user(true, null);
        User unverified = user(false, null);
        BroadcastRecipient r1 = pending(verified);
        BroadcastRecipient r2 = pending(unverified);
        given(recipientRepository.findByBroadcastIdAndStatus(broadcastId, BroadcastRecipient.Status.PENDING))
                .willReturn(List.of(r1, r2));
        given(userRepository.findById(verified.getId())).willReturn(Optional.of(verified));
        given(userRepository.findById(unverified.getId())).willReturn(Optional.of(unverified));
        given(emailVerificationService.verifyLinkFor(unverified.getId()))
                .willReturn("https://app.majstr.test/verify-email?token=abc");

        dispatcher.run(broadcastId);

        assertThat(r1.getStatus()).isEqualTo(BroadcastRecipient.Status.SKIPPED);
        assertThat(r2.getStatus()).isEqualTo(BroadcastRecipient.Status.SENT);
        ArgumentCaptor<EmailService.BroadcastEmail> mail = ArgumentCaptor.forClass(EmailService.BroadcastEmail.class);
        verify(emailService).sendBroadcast(mail.capture());
        assertThat(mail.getValue().html()).contains("verify-email?token=abc").contains("Підтвердити пошту");
    }

    @Test
    void aFailedLetterIsRecorded_andTheRestStillGoOut() {
        Broadcast b = broadcast(BroadcastSegment.NO_OBJECT);
        User a = user(true, null);
        User c = user(true, null);
        BroadcastRecipient ra = pending(a);
        BroadcastRecipient rc = pending(c);
        given(recipientRepository.findByBroadcastIdAndStatus(broadcastId, BroadcastRecipient.Status.PENDING))
                .willReturn(List.of(ra, rc));
        given(userRepository.findById(a.getId())).willReturn(Optional.of(a));
        given(userRepository.findById(c.getId())).willReturn(Optional.of(c));
        willThrow(new IllegalStateException("422 invalid to")).willDoNothing()
                .given(emailService).sendBroadcast(any());

        dispatcher.run(broadcastId);

        assertThat(ra.getStatus()).isEqualTo(BroadcastRecipient.Status.FAILED);
        assertThat(ra.getError()).isEqualTo("422 invalid to");
        assertThat(rc.getStatus()).isEqualTo(BroadcastRecipient.Status.SENT);
        assertThat(b.getFinishedAt()).isNotNull();
    }

    @Test
    void aFinishedBroadcastIsNeverRunAgain() {
        Broadcast b = broadcast(BroadcastSegment.INACTIVE);
        b.setFinishedAt(Instant.now());

        dispatcher.run(broadcastId);

        verify(recipientRepository, never()).findByBroadcastIdAndStatus(any(), any());
    }

    private Broadcast broadcast(BroadcastSegment segment) {
        Broadcast b = Broadcast.builder().id(broadcastId).segment(segment)
                .subject("{{імʼя}}, привіт").body("Вітаю, {{імʼя}}!").sentBy("admin@t.ua").build();
        given(broadcastRepository.findById(broadcastId)).willReturn(Optional.of(b));
        return b;
    }

    private static User user(boolean verified, Instant optOut) {
        User u = User.builder().id(UUID.randomUUID()).email("p@t.ua").fullName("Петро").build();
        u.setEmailVerified(verified);
        u.setBroadcastOptOutAt(optOut);
        return u;
    }

    private BroadcastRecipient pending(User u) {
        return BroadcastRecipient.builder().id(UUID.randomUUID()).broadcastId(broadcastId).userId(u.getId())
                .email(u.getEmail()).status(BroadcastRecipient.Status.PENDING).unsubscribeToken("tok").build();
    }
}
