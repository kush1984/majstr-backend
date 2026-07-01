package com.majstr.backend.controller;

import com.majstr.backend.dto.UpgradeClickRequest;
import com.majstr.backend.dto.UpgradeInterestRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.UpgradeEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PRO upgrade-intent tracking (painted door). Both endpoints are best-effort
 * analytics for a logged-in master — a failure here must not break the master's
 * UX (the PWA fires the click optimistically and ignores errors).
 */
@RestController
@RequestMapping("/api/upgrade")
@RequiredArgsConstructor
@Tag(name = "Upgrade", description = "PRO upgrade-intent tracking (painted door)")
@SecurityRequirement(name = "bearer-jwt")
public class UpgradeController {

    private final UpgradeEventService upgradeEventService;

    @Operation(summary = "Record an upgrade-CTA click (counted every time, by trigger)")
    @PostMapping("/click")
    public ResponseEntity<Void> click(@Valid @RequestBody(required = false) UpgradeClickRequest req,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        upgradeEventService.recordClick(principal.id(), req != null ? req.trigger() : null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Record PRO interest from the painted-door form (a warm lead)")
    @PostMapping("/interest")
    public ResponseEntity<Void> interest(@Valid @RequestBody(required = false) UpgradeInterestRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        upgradeEventService.recordInterest(principal.id(), req != null ? req.reason() : null);
        return ResponseEntity.noContent().build();
    }
}
