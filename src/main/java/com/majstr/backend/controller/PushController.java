package com.majstr.backend.controller;

import com.majstr.backend.config.VapidProperties;
import com.majstr.backend.dto.PushSubscribeRequest;
import com.majstr.backend.dto.PushUnsubscribeRequest;
import com.majstr.backend.dto.VapidPublicKeyResponse;
import com.majstr.backend.push.PushSubscriptionService;
import com.majstr.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
@Tag(name = "Web Push", description = "Browser push notification subscriptions")
public class PushController {

    private final VapidProperties vapidProperties;
    private final PushSubscriptionService subscriptionService;

    @Operation(summary = "Get the server's VAPID public key (null if push is not configured)")
    @GetMapping("/vapid-public-key")
    public VapidPublicKeyResponse vapidPublicKey() {
        return new VapidPublicKeyResponse(
                vapidProperties.isConfigured() ? vapidProperties.publicKey() : null);
    }

    @Operation(summary = "Subscribe the current browser to push notifications (upsert by endpoint)")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody PushSubscribeRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        subscriptionService.subscribe(principal.id(), req);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Unsubscribe the current browser from push notifications")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushUnsubscribeRequest req,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        subscriptionService.unsubscribe(principal.id(), req.endpoint());
        return ResponseEntity.noContent().build();
    }
}
