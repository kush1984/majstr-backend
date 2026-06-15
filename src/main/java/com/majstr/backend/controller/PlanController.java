package com.majstr.backend.controller;

import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
@Tag(name = "Plan", description = "Current user's plan quotas (for preemptive UI limit blocking)")
@SecurityRequirement(name = "bearer-jwt")
public class PlanController {

    private final LimitService limitService;

    @Operation(summary = "Get my plan's limits (null = unlimited) so the UI can disable create actions at the cap")
    @GetMapping("/limits")
    public PlanLimitsResponse limits(@AuthenticationPrincipal UserPrincipal principal) {
        return limitService.limitsFor(principal.id());
    }
}
