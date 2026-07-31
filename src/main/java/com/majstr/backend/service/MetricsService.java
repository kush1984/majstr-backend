package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsGrowthResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.dto.SourceCount;
import com.majstr.backend.dto.SubscriptionBreakdown;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ReferralRewardRepository;
import com.majstr.backend.repository.UpgradeEventRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
    /** Enough to see the last day's activity at a glance; the full list lives in the users screen. */
    private static final int RECENT_PAYMENTS = 10;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateShareLinkRepository shareLinkRepository;
    private final UpgradeEventRepository upgradeEventRepository;
    private final ReferralRewardRepository referralRewardRepository;

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

        SubscriptionBreakdown subscriptions = subscriptions(now, monthAgo);
        // From REAL payments, not from the plan column. (PRO + TEAM) / total counted a five-day
        // trial and an admin grant as revenue, so the figure was identical the day before and the
        // day after the first paying customer — the one day it had to move.
        BigDecimal conversion = total == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(subscriptions.everPaid())
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
                subscriptions,
                new MetricsOverviewResponse.ChurnSummary(
                        activeLastMonth.size(),
                        stillActive,
                        churned
                ),
                userRepository.countAutoRenewUsers(),
                referralRewardRepository.countAllRewards()
        );
    }

    /**
     * Bought vs trial vs admin-granted, plus the money.
     *
     * <p>The classification order is the whole point: {@code paid} is checked FIRST, so a master who
     * took the trial and then bought counts as a customer rather than as a trialist. The other two
     * are only reachable when there is no successful payment at all.</p>
     *
     * <p>{@code payingNow} additionally requires the plan to still be live, which is what separates
     * a subscriber from someone who paid once in March. Both numbers are reported because only the
     * pair distinguishes growth from churn.</p>
     */
    private SubscriptionBreakdown subscriptions(Instant now, Instant monthAgo) {
        Set<UUID> everPaidIds = paymentRepository.findEverPaidUserIds();
        long payingNow = 0;
        long onTrial = 0;
        long granted = 0;
        for (User u : userRepository.findOnPaidPlan()) {
            if (everPaidIds.contains(u.getId())) {
                // A dateless plan on a payer is an admin top-up over a real purchase; still paying.
                if (u.getPlanExpiresAt() == null || u.getPlanExpiresAt().isAfter(now)) {
                    payingNow++;
                }
            } else if (u.getTrialStartedAt() != null) {
                onTrial++;
            } else {
                granted++;
            }
        }
        return new SubscriptionBreakdown(
                payingNow,
                paymentRepository.countEverPaid(),
                onTrial,
                granted,
                paymentRepository.countSuccessfulPayments(),
                paymentRepository.sumRevenue(),
                paymentRepository.sumRevenueSince(monthAgo),
                recentPayments());
    }

    /** The newest successful payments with the payer's name — one extra query for the whole page. */
    private List<SubscriptionBreakdown.RecentPayment> recentPayments() {
        List<Payment> payments = paymentRepository.findRecentSuccessful(PageRequest.of(0, RECENT_PAYMENTS));
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<UUID, User> payers = userRepository
                .findByIdIn(payments.stream().map(Payment::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        List<SubscriptionBreakdown.RecentPayment> out = new ArrayList<>(payments.size());
        for (Payment p : payments) {
            User payer = payers.get(p.getUserId());
            out.add(new SubscriptionBreakdown.RecentPayment(
                    payer == null ? "—" : payer.getEmail(),
                    payer == null ? null : payer.getFullName(),
                    p.getAmount(),
                    String.valueOf(p.getPlan()),
                    String.valueOf(p.getPeriod()),
                    String.valueOf(p.getKind()),
                    p.getDays(),
                    p.getPaidAt()));
        }
        return out;
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

    /**
     * Admin "by referral source" report — counts only (no money; a rev-share
     * money layer comes with billing). Four grouped queries (no N+1) folded into
     * one row per source: registered, activated (has an object), and PRO clicks /
     * interest. Sorted by registrations desc.
     */
    @Transactional(readOnly = true)
    public SourceBreakdownResponse bySource() {
        Map<String, Long> registered = toSourceMap(userRepository.countUsersBySource());
        Map<String, Long> activated = toSourceMap(projectRepository.countActivatedOwnersBySource());
        Map<String, Long> clicks = toSourceMap(
                upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.CLICK));
        Map<String, Long> interested = toSourceMap(
                upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.INTEREST));

        Set<String> sources = new HashSet<>();
        sources.addAll(registered.keySet());
        sources.addAll(activated.keySet());
        sources.addAll(clicks.keySet());
        sources.addAll(interested.keySet());

        List<SourceBreakdownResponse.SourceStat> stats = sources.stream()
                .map(s -> new SourceBreakdownResponse.SourceStat(
                        s,
                        registered.getOrDefault(s, 0L),
                        activated.getOrDefault(s, 0L),
                        clicks.getOrDefault(s, 0L),
                        interested.getOrDefault(s, 0L)))
                .sorted(Comparator.comparingLong(SourceBreakdownResponse.SourceStat::registered).reversed()
                        .thenComparing(SourceBreakdownResponse.SourceStat::source))
                .toList();
        return new SourceBreakdownResponse(stats);
    }

    private static Map<String, Long> toSourceMap(List<SourceCount> rows) {
        Map<String, Long> map = new HashMap<>();
        for (SourceCount row : rows) {
            map.put(row.getSource(), row.getCnt());
        }
        return map;
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
