package com.majstr.backend.controller;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsGrowthResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.service.MetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
@Tag(name = "Admin metrics", description = "Aggregated dashboard data (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminMetricsController {

    private final MetricsService metricsService;

    @Operation(summary = "Aggregate snapshot for the admin dashboard")
    @GetMapping("/overview")
    public MetricsOverviewResponse overview() {
        return metricsService.overview();
    }

    @Operation(summary = "Activation funnel across masters (registered → verified → project → estimate → shared → signed)")
    @GetMapping("/funnel")
    public ActivationFunnelResponse funnel() {
        return metricsService.activationFunnel();
    }

    @Operation(summary = "Registrations per day over the given period")
    @GetMapping("/growth")
    public MetricsGrowthResponse growth(@RequestParam(name = "period", defaultValue = "30d") String period) {
        return metricsService.growth(parsePeriodDays(period));
    }

    /** Accepts shapes like "30d", "7d", "90d". Falls back to 30. */
    private int parsePeriodDays(String period) {
        if (period == null || period.isBlank()) return 30;
        String trimmed = period.trim().toLowerCase();
        if (trimmed.endsWith("d")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return 30;
        }
    }
}
