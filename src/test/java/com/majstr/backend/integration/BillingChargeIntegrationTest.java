package com.majstr.backend.integration;

import com.majstr.backend.billing.MonobankClient;
import com.majstr.backend.billing.MonobankSignatureVerifier;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentKind;
import com.majstr.backend.entity.PaymentProvider;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AutoRenewService;
import com.majstr.backend.service.BillingReconcileJob;
import com.majstr.backend.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The money path against real transactions. Mockito cannot see what a transaction boundary does, and
 * every bug here lived in one: a row nobody else could read yet, a charge inside a transaction that
 * could still roll back and forget it.
 */
@SpringBootTest(properties = "app.billing.monobank-token=test-token")
class BillingChargeIntegrationTest extends IntegrationTestBase {

    @MockitoBean MonobankClient monobankClient;
    @MockitoBean MonobankSignatureVerifier signatureVerifier;

    @Autowired BillingService billingService;
    @Autowired AutoRenewService autoRenewService;
    @Autowired BillingReconcileJob reconcileJob;
    @Autowired UserRepository userRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired JdbcTemplate jdbc;

    private User renewableUser() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        return userRepository.save(User.builder()
                .email(email)
                .emailCanonical(email)
                .emailVerified(true)
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.PRO)
                .role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .autoRenew(true)
                .cardToken("card-" + unique)
                .planExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());
    }

    private Payment openPayment(User user, String invoiceId, PaymentKind kind) {
        return paymentRepository.save(Payment.builder()
                .userId(user.getId())
                .provider(PaymentProvider.MONOBANK)
                .invoiceId(invoiceId)
                .amount(new BigDecimal("299.00"))
                .ccy(980)
                .status(PaymentStatus.PENDING)
                .plan(Plan.PRO)
                .days(30)
                .kind(kind)
                .build());
    }

    private void backdate(Payment payment, int minutes) {
        jdbc.update("UPDATE payments SET created_at = now() - make_interval(mins => ?) WHERE id = ?",
                minutes, payment.getId());
    }

    private static byte[] webhook(String invoiceId, long amount, String reference) {
        return ("{\"invoiceId\":\"" + invoiceId + "\",\"status\":\"success\",\"amount\":" + amount
                + ",\"reference\":\"" + reference + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private List<Payment> paymentsOf(User user) {
        return paymentRepository.findAutoRenewSince(user.getId(), Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @Test
    void aWebhookThatBeatsTheChargeResponseStillGrantsPro() {
        User user = renewableUser();
        AtomicBoolean noTransactionDuringTheCharge = new AtomicBoolean();
        given(signatureVerifier.verify(any(), any(), any())).willReturn(true);
        given(monobankClient.publicKey()).willReturn("pk");
        given(monobankClient.tokenPayment(eq(user.getCardToken()), anyLong(), anyInt(), anyString(), anyString()))
                .willAnswer(call -> {
                    noTransactionDuringTheCharge.set(!TransactionSynchronizationManager.isActualTransactionActive());
                    long amount = call.getArgument(1);
                    String reference = call.getArgument(3);
                    // Its own thread, so its own connection: it only sees what has really committed.
                    CompletableFuture.runAsync(() -> billingService.handleWebhook(
                            webhook("inv-" + reference, amount, reference), "sig")).join();
                    return "inv-" + reference;
                });

        billingService.chargeAutoRenew(user.getId());

        assertThat(noTransactionDuringTheCharge).isTrue();
        Payment payment = paymentsOf(user).getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getInvoiceId()).isEqualTo("inv-" + payment.getId());
        User after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.getPlanExpiresAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void aTimedOutChargeIsNotChargedAgainTheNextDay() {
        User user = renewableUser();
        given(monobankClient.tokenPayment(eq(user.getCardToken()), anyLong(), anyInt(), anyString(), anyString()))
                .willThrow(new ResourceAccessException("read timed out"));

        assertThatThrownBy(() -> billingService.chargeAutoRenew(user.getId()))
                .isInstanceOf(ResourceAccessException.class);

        // The row committed before the call, so it survives the exception.
        List<Payment> attempts = paymentsOf(user);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(attempts.getFirst().getInvoiceId()).isNull();

        backdate(attempts.getFirst(), 25 * 60); // past «one attempt per day»: only inFlight can stop it now
        autoRenewService.runAutoRenew();

        verify(monobankClient, times(1))
                .tokenPayment(eq(user.getCardToken()), anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void theJobGrantsWhatALostWebhookNeverDid() {
        User user = renewableUser();
        String invoiceId = "inv-" + UUID.randomUUID();
        Payment payment = openPayment(user, invoiceId, PaymentKind.CHECKOUT);
        backdate(payment, 30);
        given(monobankClient.invoiceStatus(eq(invoiceId)))
                .willReturn(Map.of("status", "success", "amount", 29900));

        reconcileJob.reconcile();

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPlanExpiresAt())
                .isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void theUnsettledQueryPicksOnlyWhatCanStillBeSettled() {
        User user = renewableUser();
        Payment withInvoice = openPayment(user, "inv-" + UUID.randomUUID(), PaymentKind.CHECKOUT);
        Payment unknownCharge = openPayment(user, null, PaymentKind.AUTO_RENEW);
        Payment neverReachedTheGateway = openPayment(user, null, PaymentKind.CHECKOUT);
        Payment settled = openPayment(user, "inv-" + UUID.randomUUID(), PaymentKind.CHECKOUT);
        settled.setStatus(PaymentStatus.SUCCESS);
        paymentRepository.save(settled);
        Payment tooOld = openPayment(user, "inv-" + UUID.randomUUID(), PaymentKind.CHECKOUT);
        backdate(tooOld, 60 * 24 * 40);

        Instant now = Instant.now();
        List<UUID> found = paymentRepository.findUnsettled(now.plus(1, ChronoUnit.MINUTES),
                        now.minus(30, ChronoUnit.DAYS), PageRequest.of(0, 1000)).stream()
                .map(Payment::getId)
                .toList();

        assertThat(found).contains(withInvoice.getId(), unknownCharge.getId());
        assertThat(found).doesNotContain(neverReachedTheGateway.getId(), settled.getId(), tooOld.getId());
    }
}
