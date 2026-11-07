package com.majstr.backend.controller;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.CrewUsageResponse;
import com.majstr.backend.dto.HelpRequestLead;
import com.majstr.backend.dto.MetricsGrowthResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.dto.UpgradeInterestResponse;
import com.majstr.backend.dto.VisitStatsResponse;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.MetricsService;
import com.majstr.backend.service.SiteVisitService;
import com.majstr.backend.service.UpgradeEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.UUID;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.transaction.annotation.Transactional;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.entity.User;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
@Tag(name = "Admin metrics", description = "Aggregated dashboard data (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminMetricsController {

    private final MetricsService metricsService;
    private final UpgradeEventService upgradeEventService;
    private final SiteVisitService siteVisitService;
    private final UserRepository userRepository;

    @Operation(summary = "Aggregate snapshot for the admin dashboard")
    @GetMapping("/overview")
    public MetricsOverviewResponse overview() {
        return metricsService.overview();
    }

    @Operation(summary = "PRO upgrade intent: distinct clickers, total clicks, by-trigger, warm leads")
    @GetMapping("/upgrade-interest")
    public UpgradeInterestResponse upgradeInterest() {
        return upgradeEventService.interestStats();
    }

    @Operation(summary = "Masters who asked for a setup call, newest first, with the phone they left (V191)")
    @GetMapping("/help-requests")
    public List<HelpRequestLead> helpRequests() {
        return userRepository.findTop200ByHelpRequestedAtIsNotNullOrderByHelpRequestedAtDesc().stream()
                .map(u -> new HelpRequestLead(u.getId(), u.getEmail(), u.getFullName(),
                        u.getHelpPhone() != null ? u.getHelpPhone() : u.getPhone(),
                        u.getHelpRequestedAt(), u.getHelpHandledAt()))
                .toList();
    }

    @Operation(summary = "Mark a setup call done (or undo it): the list stays a to-do list (V193)")
    @PostMapping("/help-requests/{userId}/handled")
    @Transactional
    public HelpRequestLead markHelpHandled(@PathVariable UUID userId, @RequestParam(defaultValue = "true") boolean handled) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        u.setHelpHandledAt(handled ? Instant.now() : null);
        return new HelpRequestLead(u.getId(), u.getEmail(), u.getFullName(),
                u.getHelpPhone() != null ? u.getHelpPhone() : u.getPhone(), u.getHelpRequestedAt(), u.getHelpHandledAt());
    }

    @Operation(summary = "Activation funnel across masters (registered → verified → project → estimate → shared → signed)")
    @GetMapping("/funnel")
    public ActivationFunnelResponse funnel() {
        return metricsService.activationFunnel();
    }

    @Operation(summary = "How many masters work with a crew — a FLOOR, counted from duplicate-with-markup usage")
    @GetMapping("/crew-usage")
    public CrewUsageResponse crewUsage() {
        return metricsService.crewUsage();
    }

    @Operation(summary = "Registrations / activation / PRO interest grouped by referral source (partner rev-share)")
    @GetMapping("/by-source")
    public SourceBreakdownResponse bySource() {
        return metricsService.bySource();
    }

    @Operation(summary = "Anonymous visits to the public pages: visitors, sources, devices, and registrations "
            + "per source over the given period")
    @GetMapping("/visits")
    public VisitStatsResponse visits(@RequestParam(name = "period", defaultValue = "30d") String period) {
        return siteVisitService.stats(parsePeriodDays(period));
    }

    @Operation(summary = "Registrations per day over the given period")
    @GetMapping("/growth")
    public MetricsGrowthResponse growth(@RequestParam(name = "period", defaultValue = "30d") String period) {
        return metricsService.growth(parsePeriodDays(period));
    }

    /** Accepts shapes like "30d", "7d", "90d". Falls back to 30. */
    private int parsePeriodDays(String period) {
        if (period == null || period.isBlank()) return 30;
        String trimmed = period.trim().toLowerCase(Locale.ROOT);
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
