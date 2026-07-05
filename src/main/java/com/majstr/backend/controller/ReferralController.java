package com.majstr.backend.controller;

import com.majstr.backend.dto.ReferralStatsResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ReferralQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master→master referral stats for the profile "Запроси майстра" panel. The
 * personal code itself also rides on {@code /auth/me} (UserResponse); this endpoint
 * adds the invited / paid / months-earned counts.
 */
@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
@Tag(name = "Referrals", description = "Master→master referral program")
@SecurityRequirement(name = "bearer-jwt")
public class ReferralController {

    private final ReferralQueryService referralQueryService;

    @Operation(summary = "This master's referral stats (code + invited/paid/months earned)")
    @GetMapping("/me")
    public ReferralStatsResponse myReferrals(@AuthenticationPrincipal UserPrincipal principal) {
        return referralQueryService.stats(principal.id());
    }
}
