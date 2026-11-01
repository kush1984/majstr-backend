package com.majstr.backend.controller;

import com.majstr.backend.dto.CheckoutRequest;
import com.majstr.backend.dto.CheckoutResponse;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.BillingPeriod;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "PRO subscription payments (monobank acquiring)")
public class BillingController {

    private final BillingService billingService;
    private final UserTradeRepository userTradeRepository;

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
        var user = billingService.startTrial(principal.id());
        return UserResponse.from(user, userTradeRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId()));
    }

    @Operation(summary = "monobank invoice-status webhook (public; signature-verified)")
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = "X-Sign", required = false) String sign) {
        // 200 means «stop delivering», so it is only for what was handled or is not ours to act on
        // (a forged signature, an unknown invoice), both of which handleWebhook returns from. A failure
        // applying a genuine webhook escapes to the advice as a 5xx: monobank tries up to three times,
        // and answering 200 here used to turn one database blip into money taken and no PRO.
        billingService.handleWebhook(body, sign);
        return ResponseEntity.ok().build();
    }
}
