package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsGrowthResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.dto.SourceCount;
import com.majstr.backend.dto.SubscriptionBreakdown;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
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

    /**
     * Registrations a source needs before its percentages are worth reading — and before it is
     * allowed to sort by them at all.
     *
     * <p>Five, not ten: at the current scale ten would push every source into the "not enough data"
     * group and the table would look broken. It is a reading aid, not a statistical claim — three
     * registrations against five is still not analytics.</p>
     */
    static final int SIGNIFICANT_SOURCE_MIN_REGISTRATIONS = 5;

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateShareLinkRepository shareLinkRepository;
    private final ProjectShareLinkRepository projectShareLinkRepository;
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
     * signature. Each step is one aggregate query (no per-user loop).
     *
     * <p>Every step filters {@code role = USER} explicitly. It used to rely on "admins have no
     * projects", which is true only until somebody makes one demo object on the admin account — at
     * which point the by-source report (which always filtered the role) would stop summing to this
     * one, for a purely technical reason.</p>
     *
     * <p>What {@code shared} means is spelled out on {@link #sharedWithClientCount()}; read it before
     * comparing this number to anything recorded before the analytics-shared-fix iteration.</p>
     */
    @Transactional(readOnly = true)
    public ActivationFunnelResponse activationFunnel() {
        return new ActivationFunnelResponse(
                userRepository.countByRole(Role.USER),
                userRepository.countByRoleAndEmailVerifiedTrue(Role.USER),
                projectRepository.countDistinctOwners(),
                estimateRepository.countDistinctProjectOwners(),
                sharedOwnerSources().size(),
                estimateRepository.countDistinctProjectOwnersByStatus(EstimateStatus.SIGNED)
        );
    }

    /**
     * Masters who ever put a document in front of a client, over BOTH link tables.
     *
     * <p><b>Why both.</b> This step used to read {@code EstimateShareLink} alone — the per-estimate
     * {@code ?t=} link. Sharing from the object goes through {@code ProjectShareLink} instead
     * ({@code ?p=} PORTAL, {@code ?e=} ECONOMY, {@code ?a=} ACT), and that is the main flow since the
     * portal iteration, so the step drew a cliff that did not exist.</p>
     *
     * <p><b>Union of id sets, not a sum of two counts.</b> A master commonly has both kinds of link;
     * adding the counts would count them twice and could even push the step above the previous one.
     * Two id selects are cheap here — the result is bounded by the number of masters, not links.</p>
     *
     * <p><b>MESSAGE is excluded</b> ({@link ShareLinkKind#SHARED_WITH_CLIENT}) — it opens a contact
     * form, often minted for a supplier. <b>{@code revoked}/{@code expires_at} are not filtered</b> —
     * the step means "ever shared", and a filtered step would shrink over time.</p>
     *
     * <p><b>The two halves are not equally honest.</b> The object half is: a row appears only on a
     * deliberate publish. The estimate half is inflated — the PWA mints the per-estimate link in an
     * effect when the share sheet OPENS, before anything is copied or sent. So this step reads
     * "published on the object portal OR opened the estimate share sheet". Fixing that is a PWA
     * change (mint lazily) and is deliberately out of scope here.</p>
     */
    private Map<UUID, String> sharedOwnerSources() {
        Map<UUID, String> owners = new HashMap<>();
        for (OwnerSource row : shareLinkRepository.findSharedOwners()) {
            owners.put(row.getOwnerId(), row.getSource());
        }
        for (OwnerSource row : projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT)) {
            owners.put(row.getOwnerId(), row.getSource());
        }
        return owners;
    }

    /**
     * Admin "by referral source" report — the WHOLE activation funnel per source, plus the two
     * PRO-interest counters. Counts only; a rev-share money layer comes with billing.
     *
     * <p>The report used to stop at {@code activated} — step 3 of 6 — which cannot answer the one
     * question a channel is judged by: <b>which source brings masters who reach a SIGNED
     * estimate</b>. Sign-ups are cheap; the signature is the product working.</p>
     *
     * <p>Grouped queries folded through {@link #toSourceMap}, never a loop over users. The one step
     * that cannot be a {@code GROUP BY} is {@code shared}: it is a union over two link tables, so
     * the ids are de-duplicated in Java first — by {@link #sharedOwnerSources()}, the same
     * computation the aggregate funnel uses, which is what keeps the two reports summing to each
     * other.</p>
     *
     * <p><b>This service owns the row order</b> (the page renders what it gets, in order). Default:
     * "% to signed" descending — but ONLY among sources with at least
     * {@link #SIGNIFICANT_SOURCE_MIN_REGISTRATIONS} registrations, because one registration that
     * signs once is 100 % and would otherwise sit on top forever. Everything below the threshold
     * follows, ordered by registrations, flagged {@code enoughData = false}. If ordering ever moves
     * to the client, delete this comparator rather than leave two orderings arguing.</p>
     */
    @Transactional(readOnly = true)
    public SourceBreakdownResponse bySource() {
        Map<String, Long> registered = toSourceMap(userRepository.countUsersBySource());
        Map<String, Long> verified = toSourceMap(userRepository.countVerifiedUsersBySource());
        Map<String, Long> activated = toSourceMap(projectRepository.countActivatedOwnersBySource());
        Map<String, Long> withEstimate = toSourceMap(estimateRepository.countEstimateOwnersBySource());
        Map<String, Long> withSigned = toSourceMap(
                estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED));
        Map<String, Long> clicks = toSourceMap(
                upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.CLICK));
        Map<String, Long> interested = toSourceMap(
                upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.INTEREST));
        Map<String, Long> shared = sharedOwnerSources().values().stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        Set<String> sources = new HashSet<>();
        for (Map<String, Long> step : List.of(
                registered, verified, activated, withEstimate, withSigned, shared, clicks, interested)) {
            sources.addAll(step.keySet());
        }

        List<SourceBreakdownResponse.SourceStat> stats = sources.stream()
                .map(s -> new SourceBreakdownResponse.SourceStat(
                        s,
                        registered.getOrDefault(s, 0L),
                        verified.getOrDefault(s, 0L),
                        activated.getOrDefault(s, 0L),
                        withEstimate.getOrDefault(s, 0L),
                        shared.getOrDefault(s, 0L),
                        withSigned.getOrDefault(s, 0L),
                        clicks.getOrDefault(s, 0L),
                        interested.getOrDefault(s, 0L),
                        registered.getOrDefault(s, 0L) >= SIGNIFICANT_SOURCE_MIN_REGISTRATIONS))
                .sorted(BY_SIGNED_RATE)
                .toList();
        return new SourceBreakdownResponse(stats, SIGNIFICANT_SOURCE_MIN_REGISTRATIONS, utmStats());
    }

    /** Comparable rows first, best "% to signed" first within each group. */
    private static final Comparator<SourceBreakdownResponse.SourceStat> BY_SIGNED_RATE =
            Comparator.<SourceBreakdownResponse.SourceStat, Boolean>comparing(
                            SourceBreakdownResponse.SourceStat::enoughData).reversed()
                    .thenComparing(Comparator.<SourceBreakdownResponse.SourceStat>comparingDouble(
                            MetricsService::signedRate).reversed())
                    .thenComparing(Comparator.<SourceBreakdownResponse.SourceStat>comparingLong(
                            SourceBreakdownResponse.SourceStat::registered).reversed())
                    .thenComparing(SourceBreakdownResponse.SourceStat::source);

    /** Below the threshold the rate is not a number worth ordering by — flatten it to 0 so those
     *  rows fall back to "most registrations first" instead of a 100 % row made of one master. */
    private static double signedRate(SourceBreakdownResponse.SourceStat row) {
        if (!row.enoughData() || row.registered() == 0) {
            return 0d;
        }
        return (double) row.withSigned() / row.registered();
    }

    /**
     * The channel dimension (V114) — first-touch UTM, both ends of the funnel only.
     *
     * <p>Separate from the partner dimension above on purpose: {@code ref} is a PARTNER (money,
     * rev-share, the {@code partners} registry), UTM is a CHANNEL, and a master can arrive on a
     * partner link from TikTok. Folded into one column, both dimensions would be lost.</p>
     *
     * <p>Two steps, not six: a channel is judged by "% to signed", and the middle steps are worth
     * their four extra grouped queries only once there is volume to read them at.</p>
     *
     * <p><b>The NULL key is the point.</b> {@code utm_source} is nullable and NULL means "arrived
     * with no tags" — the largest bucket today. It is carried through as a null {@code source} and
     * labelled «без UTM» by the page: dropping it would quietly shrink the report below the
     * registration total.</p>
     */
    private List<SourceBreakdownResponse.UtmStat> utmStats() {
        Map<String, Long> registered = toSourceMap(userRepository.countUsersByUtmSource());
        Map<String, Long> signed = toSourceMap(
                estimateRepository.countOwnersByStatusAndUtmSource(EstimateStatus.SIGNED));

        Set<String> tags = new HashSet<>();
        tags.addAll(registered.keySet());
        tags.addAll(signed.keySet());

        return tags.stream()
                .map(t -> new SourceBreakdownResponse.UtmStat(
                        t,
                        registered.getOrDefault(t, 0L),
                        signed.getOrDefault(t, 0L),
                        registered.getOrDefault(t, 0L) >= SIGNIFICANT_SOURCE_MIN_REGISTRATIONS))
                .sorted(Comparator.comparingLong(SourceBreakdownResponse.UtmStat::registered).reversed()
                        .thenComparing(u -> u.source() == null ? "" : u.source()))
                .toList();
    }

    /** Grouped rows folded into a map. A {@link HashMap} on purpose: the UTM grouping column is
     *  NULLABLE and its null key is a real bucket («без UTM») that must survive the fold. */
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
