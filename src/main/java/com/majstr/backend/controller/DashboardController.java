package com.majstr.backend.controller;

import com.majstr.backend.dto.DashboardMetricsResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Home-screen metrics for the current contractor")
@SecurityRequirement(name = "bearer-jwt")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Metrics for the current contractor's dashboard (active projects, pending estimates, completed this month)")
    @GetMapping("/metrics")
    public DashboardMetricsResponse metrics(@AuthenticationPrincipal UserPrincipal principal) {
        return dashboardService.metrics(principal.id());
    }
}
