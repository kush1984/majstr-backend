package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentKind;
import com.majstr.backend.entity.PaymentStatus;
import com.majstr.backend.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BillingReconcileJobTest {

    @Mock BillingService billingService;
    @Mock PaymentRepository paymentRepository;

    private BillingReconcileJob job(String token) {
        BillingProperties props = new BillingProperties(token, "https://api.monobank.ua",
                new BigDecimal("299"), 30, new BigDecimal("1494"), new BigDecimal("2748"), 3,
                "http://ret", "http://hook", false, 3, 30, 15, 3);
        return new BillingReconcileJob(props, billingService, paymentRepository);
    }

    private static Payment payment(String invoiceId) {
        return Payment.builder().id(UUID.randomUUID()).invoiceId(invoiceId).kind(PaymentKind.AUTO_RENEW)
                .status(PaymentStatus.PENDING).createdAt(Instant.now().minus(3, ChronoUnit.HOURS)).build();
    }

    @Test
    void asksAboutEveryRowThatHasAnInvoice() {
        Payment a = payment("inv-a");
        Payment b = payment("inv-b");
        given(paymentRepository.findUnsettled(any(), any(), any())).willReturn(List.of(a, b));

        job("tok").reconcile();

        verify(billingService).reconcile(a);
        verify(billingService).reconcile(b);
    }

    @Test
    void oneBadRowDoesNotStopTheRest() {
        Payment bad = payment("inv-bad");
        Payment unknown = payment("inv-unknown");
        Payment good = payment("inv-good");
        given(paymentRepository.findUnsettled(any(), any(), any())).willReturn(List.of(bad, unknown, good));
        doThrow(new IllegalStateException("boom")).when(billingService).reconcile(bad);
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "no", HttpHeaders.EMPTY, new byte[0], null))
                .when(billingService).reconcile(unknown);

        job("tok").reconcile();

        verify(billingService).reconcile(good);
    }

    @Test
    void aChargeWithNoInvoiceIdIsNeverClosedByGuessing() {
        Payment unknown = payment(null);
        given(paymentRepository.findUnsettled(any(), any(), any())).willReturn(List.of(unknown));

        job("tok").reconcile();

        verify(billingService, never()).reconcile(any());
        // Still open: it is what keeps AutoRenewService from charging the card a second time.
        assertThat(unknown.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void doesNothingWithoutAMerchantToken() {
        job("").reconcile();

        verifyNoInteractions(paymentRepository, billingService);
    }
}
