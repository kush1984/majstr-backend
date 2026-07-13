package com.majstr.backend.controller;

import com.majstr.backend.dto.CheckoutRequest;
import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.BillingPeriod;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription billing. {@code POST /checkout} (authed) starts a PRO purchase and
 * returns the payment page URL; {@code POST /webhook} (public) receives the
 * monobank invoice-status callback. PRO is granted only by the verified webhook —
 * never by the client.
 */
@Slf4j
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "PRO subscription payments (monobank acquiring)")
public class BillingController {

    private final BillingService billingService;

    @Operation(summary = "Start a PRO checkout — returns the monobank payment page URL to redirect to")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/checkout")
    public CheckoutResponse checkout(@RequestBody(required = false) CheckoutRequest req,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        boolean autoRenew = req != null && req.autoRenew();
        BillingPeriod period = req != null ? req.periodOrDefault() : BillingPeriod.MONTH;
        return billingService.checkout(principal.id(), autoRenew, period);
    }

    @Operation(summary = "Start the one-time self-serve 5-day PRO trial (FREE only, no card) — returns the updated profile")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/trial")
    public UserResponse startTrial(@AuthenticationPrincipal UserPrincipal principal) {
        return UserResponse.from(billingService.startTrial(principal.id()));
    }

    @Operation(summary = "monobank invoice-status webhook (public; signature-verified)")
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = "X-Sign", required = false) String sign) {
        // Always 200 after handling: the work is idempotent, and a forged/invalid
        // request is ignored inside — returning 200 avoids a monobank retry storm.
        try {
            billingService.handleWebhook(body, sign);
        } catch (Exception e) {
            log.error("Error handling billing webhook: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
