package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsGrowthResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side aggregations for the admin dashboard. Everything is computed
 * from the existing tables — no separate metrics store. Counts that fit
 * in one query go through {@link UserRepository} derived methods; the
 * growth chart aggregates in-memory because it's bounded by the period.
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private static final int ACTIVE_WINDOW_DAYS = 30;

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateShareLinkRepository shareLinkRepository;

    @Transactional(readOnly = true)
    public MetricsOverviewResponse overview() {
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        Instant monthAgo = now.minus(30, ChronoUnit.DAYS);
        Instant twoMonthsAgo = now.minus(60, ChronoUnit.DAYS);
        Instant activeWindow = now.minus(ACTIVE_WINDOW_DAYS, ChronoUnit.DAYS);

        long total = userRepository.count();
        long newToday = userRepository.countByCreatedAtAfter(startOfToday);
        long newWeek = userRepository.countByCreatedAtAfter(weekAgo);
        long newMonth = userRepository.countByCreatedAtAfter(monthAgo);
        long active30d = userRepository.countByLastActiveAtAfter(activeWindow);

        Map<Plan, Long> planDistribution = new EnumMap<>(Plan.class);
        for (Plan p : Plan.values()) {
            planDistribution.put(p, 0L);
        }
        userRepository.countGroupByPlan()
                .forEach(row -> planDistribution.put(row.getPlan(), row.getTotal()));

        long paid = planDistribution.getOrDefault(Plan.PRO, 0L)
                + planDistribution.getOrDefault(Plan.TEAM, 0L);
        BigDecimal conversion = total == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(paid)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        // Churn approximation: users active in the previous 30-day window
        // who are not active in the current 30-day window.
        Set<UUID> activeLastMonth = userIdsActiveBetween(twoMonthsAgo, monthAgo);
        Set<UUID> activeNow = userIdsActiveBetween(activeWindow, now);
        long stillActive = activeLastMonth.stream().filter(activeNow::contains).count();
        long churned = activeLastMonth.size() - stillActive;

        return new MetricsOverviewResponse(
                total,
                newToday,
                newWeek,
                newMonth,
                active30d,
                planDistribution,
                conversion,
                new MetricsOverviewResponse.ChurnSummary(
                        activeLastMonth.size(),
                        stillActive,
                        churned
                )
        );
    }

    /**
     * Activation funnel across masters (ROLE_USER): registered → verified email →
     * created a project → created an estimate → shared with a client → got a
     * signature. Each step is one aggregate COUNT (no per-user loop). The
     * distinct-owner steps are naturally master-only (admins have no projects).
     */
    @Transactional(readOnly = true)
    public ActivationFunnelResponse activationFunnel() {
        return new ActivationFunnelResponse(
                userRepository.countByRole(Role.USER),
                userRepository.countByRoleAndEmailVerifiedTrue(Role.USER),
                projectRepository.countDistinctOwners(),
                estimateRepository.countDistinctProjectOwners(),
                shareLinkRepository.countDistinctOwners(),
                estimateRepository.countDistinctProjectOwnersByStatus(EstimateStatus.SIGNED)
        );
    }

    @Transactional(readOnly = true)
    public MetricsGrowthResponse growth(int days) {
        if (days <= 0) {
            days = 30;
        }
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        LocalDate from = since.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate to = LocalDate.now(ZoneOffset.UTC);

        Map<LocalDate, Long> counts = userRepository.findRegisteredSince(since).stream()
                .collect(Collectors.groupingBy(
                        u -> u.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        Collectors.counting()
                ));

        List<MetricsGrowthResponse.Point> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            points.add(new MetricsGrowthResponse.Point(day, counts.getOrDefault(day, 0L)));
        }
        return new MetricsGrowthResponse(from, to, points);
    }

    private Set<UUID> userIdsActiveBetween(Instant fromInclusive, Instant toExclusive) {
        // For small instance sizes this in-memory filter is fine; swap for
        // a dedicated count query if user counts blow past ~100k.
        Set<UUID> result = new HashSet<>();
        for (User u : userRepository.findAll()) {
            Instant last = u.getLastActiveAt();
            if (last != null && !last.isBefore(fromInclusive) && last.isBefore(toExclusive)) {
                result.add(u.getId());
            }
        }
        return result;
    }
}
