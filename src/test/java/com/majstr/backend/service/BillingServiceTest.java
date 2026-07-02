package com.majstr.backend.service;

import com.majstr.backend.billing.MonobankClient;
import com.majstr.backend.billing.MonobankSignatureVerifier;
import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock MonobankClient monobankClient;
    @Mock MonobankSignatureVerifier signatureVerifier;
    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private BillingProperties props(String token) {
        return props(token, true);
    }

    private BillingProperties props(String token, boolean allowDevSim) {
        return new BillingProperties(token, "https://api.monobank.ua",
                new BigDecimal("299"), 30, 3,
                "http://ret", "http://hook", allowDevSim);
    }

    private BillingService service(BillingProperties props) {
        return new BillingService(props, monobankClient, signatureVerifier,
                paymentRepository, userRepository, mapper);
    }

    private User freeUser(UUID id) {
        return User.builder().id(id).email("m@x").plan(Plan.FREE).build();
    }

    @Test
    void checkout_devMode_grantsProImmediatelyAndReturnsReturnUrl() {
        UUID uid = UUID.randomUUID();
        User user = freeUser(uid);
        given(userRepository.findById(uid)).willReturn(Optional.of(user));
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        given(paymentRepository.save(saved.capture())).willAnswer(i -> i.getArgument(0));

        CheckoutResponse resp = service(props("")).checkout(uid); // blank token → dev

        assertThat(resp.pageUrl()).isEqualTo("http://ret");
        assertThat(user.getPlan()).isEqualTo(Plan.PRO);
        assertThat(user.getPlanExpiresAt()).isCloseTo(
                Instant.now().plus(30, ChronoUnit.DAYS), within60s());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(userRepository).save(user);
    }

    @Test
    void checkout_noToken_withSimulationDisabled_throwsAndGrantsNothing() {
        // Prod footgun guard: no MONOBANK_TOKEN + dev-sim off must NOT grant free PRO.
        UUID uid = UUID.randomUUID();
        User user = freeUser(uid);
        given(userRepository.findById(uid)).willReturn(Optional.of(user));
        given(paymentRepository.save(any(Payment.class))).willAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service(props("", false)).checkout(uid))
                .isInstanceOf(IllegalStateException.class);
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void checkout_configured_createsInvoiceAndDoesNotGrantYet() {
        UUID uid = UUID.randomUUID();
        User user = freeUser(uid);
        given(userRepository.findById(uid)).willReturn(Optional.of(user));
        given(paymentRepository.save(any(Payment.class))).willAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID()); // simulate @PrePersist
            return p;
        });
        given(monobankClient.createInvoice(anyLong(), eq(980), anyString(), anyString()))
                .willReturn(new MonobankClient.InvoiceCreated("inv123", "http://pay"));

        CheckoutResponse resp = service(props("tok")).checkout(uid);

        assertThat(resp.pageUrl()).isEqualTo("http://pay");
        assertThat(user.getPlan()).isEqualTo(Plan.FREE); // granted only by the webhook
        verify(userRepository, never()).save(any());
    }

    @Test
    void webhook_success_grantsProAndIsAmountChecked() {
        UUID uid = UUID.randomUUID();
        User user = freeUser(uid);
        Payment payment = pending(uid, "inv1");
        given(signatureVerifier.verify(any(), any(), any())).willReturn(true);
        given(monobankClient.publicKey()).willReturn("pk");
        given(paymentRepository.findByInvoiceId("inv1")).willReturn(Optional.of(payment));
        given(userRepository.findById(uid)).willReturn(Optional.of(user));

        service(props("tok")).handleWebhook(body("inv1", "success", 29900), "sig");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(user.getPlan()).isEqualTo(Plan.PRO);
        assertThat(user.getPlanExpiresAt()).isCloseTo(
                Instant.now().plus(30, ChronoUnit.DAYS), within60s());
        verify(userRepository).save(user);
    }

    @Test
    void webhook_success_stacksOntoStillActiveSubscription() {
        UUID uid = UUID.randomUUID();
        Instant activeUntil = Instant.now().plus(10, ChronoUnit.DAYS);
        User user = freeUser(uid);
        user.setPlan(Plan.PRO);
        user.setPlanExpiresAt(activeUntil);
        Payment payment = pending(uid, "inv1");
        given(signatureVerifier.verify(any(), any(), any())).willReturn(true);
        given(monobankClient.publicKey()).willReturn("pk");
        given(paymentRepository.findByInvoiceId("inv1")).willReturn(Optional.of(payment));
        given(userRepository.findById(uid)).willReturn(Optional.of(user));

        service(props("tok")).handleWebhook(body("inv1", "success", 29900), "sig");

        // Extends from the current end, not from now.
        assertThat(user.getPlanExpiresAt()).isCloseTo(
                activeUntil.plus(30, ChronoUnit.DAYS), within60s());
    }

    @Test
    void webhook_amountMismatch_doesNotGrant() {
        UUID uid = UUID.randomUUID();
        User user = freeUser(uid);
        Payment payment = pending(uid, "inv1");
        given(signatureVerifier.verify(any(), any(), any())).willReturn(true);
        given(monobankClient.publicKey()).willReturn("pk");
        given(paymentRepository.findByInvoiceId("inv1")).willReturn(Optional.of(payment));

        service(props("tok")).handleWebhook(body("inv1", "success", 10000), "sig"); // 100.00, not 299.00

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void webhook_invalidSignature_isIgnored() {
        given(signatureVerifier.verify(any(), any(), any())).willReturn(false);
        given(monobankClient.publicKey()).willReturn("pk");

        service(props("tok")).handleWebhook(body("inv1", "success", 29900), "bad");

        verify(paymentRepository, never()).findByInvoiceId(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void webhook_alreadySuccess_isIdempotent() {
        Payment payment = pending(UUID.randomUUID(), "inv1");
        payment.setStatus(PaymentStatus.SUCCESS);
        given(signatureVerifier.verify(any(), any(), any())).willReturn(true);
        given(monobankClient.publicKey()).willReturn("pk");
        given(paymentRepository.findByInvoiceId("inv1")).willReturn(Optional.of(payment));

        service(props("tok")).handleWebhook(body("inv1", "success", 29900), "sig");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    private static Payment pending(UUID uid, String invoiceId) {
        return Payment.builder()
                .id(UUID.randomUUID()).userId(uid).invoiceId(invoiceId)
                .amount(new BigDecimal("299")).ccy(980)
                .status(PaymentStatus.PENDING).plan(Plan.PRO).days(30)
                .build();
    }

    private static byte[] body(String invoiceId, String status, long amount) {
        return ("{\"invoiceId\":\"" + invoiceId + "\",\"status\":\"" + status
                + "\",\"amount\":" + amount + ",\"ccy\":980}").getBytes(StandardCharsets.UTF_8);
    }

    private static org.assertj.core.data.TemporalUnitOffset within60s() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(60, ChronoUnit.SECONDS);
    }
}
