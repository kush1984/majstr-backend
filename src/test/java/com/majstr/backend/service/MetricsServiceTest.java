package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.dto.SourceCount;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UpgradeEventRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock UserRepository userRepository;
    @Mock ProjectRepository projectRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock EstimateShareLinkRepository shareLinkRepository;
    @Mock com.majstr.backend.repository.ProjectShareLinkRepository projectShareLinkRepository;
    @Mock com.majstr.backend.repository.ReferralRewardRepository referralRewardRepository;
    @Mock UpgradeEventRepository upgradeEventRepository;
    @Mock PaymentRepository paymentRepository;
    @InjectMocks MetricsService metricsService;

    /** The boilerplate every overview test needs; money defaults to none. */
    private void noPayments() {
        given(paymentRepository.sumRevenue()).willReturn(BigDecimal.ZERO);
        given(paymentRepository.sumRevenueSince(any(Instant.class))).willReturn(BigDecimal.ZERO);
    }

    @Test
    void overview_conversionCountsWHOPAID_notWhoIsOnAPAIDPLAN() {
        // The distinction this test exists for: three masters are on PRO/TEAM, and exactly ONE of
        // them has ever paid. The old metric said 30% and would have said 30% the day before the
        // first sale too — it read the plan column, which a trial and an admin grant also set.
        UUID buyer = UUID.randomUUID();
        given(userRepository.count()).willReturn(10L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of(
                planCount(Plan.FREE, 7L),
                planCount(Plan.PRO, 2L),
                planCount(Plan.TEAM, 1L)
        ));
        given(userRepository.findOnPaidPlan()).willReturn(List.of(
                User.builder().id(buyer).plan(Plan.PRO)
                        .planExpiresAt(Instant.now().plus(20, ChronoUnit.DAYS)).build(),
                User.builder().id(UUID.randomUUID()).plan(Plan.PRO)
                        .trialStartedAt(Instant.now()).build(),
                User.builder().id(UUID.randomUUID()).plan(Plan.TEAM).build()));
        given(paymentRepository.findEverPaidUserIds()).willReturn(Set.of(buyer));
        given(paymentRepository.countEverPaid()).willReturn(1L);
        noPayments();

        MetricsOverviewResponse overview = metricsService.overview();

        // The plan distribution is untouched — it is still a true statement about plans.
        assertThat(overview.planDistribution())
                .containsEntry(Plan.FREE, 7L).containsEntry(Plan.PRO, 2L).containsEntry(Plan.TEAM, 1L);
        // …but the conversion is 1 payer of 10, not 3 plans of 10.
        assertThat(overview.conversionRatePercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void overview_splitsPaidPlansIntoBOUGHTvsTRIALvsGRANTED() {
        // «PRO: 2» told the owner nothing about the business. These three states have to be
        // separate numbers or the first real subscription is invisible inside the total.
        UUID buyer = UUID.randomUUID();
        UUID lapsedBuyer = UUID.randomUUID();
        given(userRepository.count()).willReturn(10L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of());
        given(userRepository.findOnPaidPlan()).willReturn(List.of(
                // paid, still live
                User.builder().id(buyer).plan(Plan.PRO)
                        .planExpiresAt(Instant.now().plus(20, ChronoUnit.DAYS)).build(),
                // paid once, subscription already run out — NOT paying now, but still ever-paid
                User.builder().id(lapsedBuyer).plan(Plan.PRO)
                        .planExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS)).build(),
                // trial only
                User.builder().id(UUID.randomUUID()).plan(Plan.PRO)
                        .trialStartedAt(Instant.now()).build(),
                // admin-granted: no payment, no trial, no expiry
                User.builder().id(UUID.randomUUID()).plan(Plan.TEAM).build()));
        given(paymentRepository.findEverPaidUserIds()).willReturn(Set.of(buyer, lapsedBuyer));
        given(paymentRepository.countEverPaid()).willReturn(2L);
        given(paymentRepository.countSuccessfulPayments()).willReturn(3L); // one of them renewed
        given(paymentRepository.sumRevenue()).willReturn(new BigDecimal("897.00"));
        given(paymentRepository.sumRevenueSince(any(Instant.class))).willReturn(new BigDecimal("299.00"));

        var subs = metricsService.overview().subscriptions();

        assertThat(subs.payingNow()).as("тільки той, у кого підписка ще жива").isEqualTo(1L);
        assertThat(subs.everPaid()).as("той, хто не продовжив, лишається в конверсії").isEqualTo(2L);
        assertThat(subs.onTrial()).isEqualTo(1L);
        assertThat(subs.granted()).isEqualTo(1L);
        assertThat(subs.successfulPayments()).isEqualTo(3L);
        assertThat(subs.revenueTotal()).isEqualByComparingTo("897.00");
        assertThat(subs.revenue30d()).isEqualByComparingTo("299.00");
    }

    @Test
    void overview_aTrialistWhoThenBUYScountsAsACUSTOMER_notAsATrialist() {
        // Order of classification, and it is the common path: the trial is how people arrive.
        UUID converted = UUID.randomUUID();
        given(userRepository.count()).willReturn(1L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of());
        given(userRepository.findOnPaidPlan()).willReturn(List.of(
                User.builder().id(converted).plan(Plan.PRO)
                        .trialStartedAt(Instant.now().minus(9, ChronoUnit.DAYS))
                        .planExpiresAt(Instant.now().plus(21, ChronoUnit.DAYS)).build()));
        given(paymentRepository.findEverPaidUserIds()).willReturn(Set.of(converted));
        given(paymentRepository.countEverPaid()).willReturn(1L);
        noPayments();

        var subs = metricsService.overview().subscriptions();

        assertThat(subs.payingNow()).isEqualTo(1L);
        assertThat(subs.onTrial()).as("trialStartedAt лишається назавжди — це не робить його пробним")
                .isZero();
    }

    @Test
    void overview_zeroUsersReturnsZeroConversionInsteadOfDivideByZero() {
        given(userRepository.count()).willReturn(0L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of());
        noPayments();

        MetricsOverviewResponse overview = metricsService.overview();

        assertThat(overview.conversionRatePercent()).isEqualByComparingTo("0");
    }

    @Test
    void overview_churnCountsLastMonthActiveWhoAreNotActiveThisMonth() {
        Instant now = Instant.now();
        Instant inThisMonth = now.minus(5, ChronoUnit.DAYS);
        Instant inLastMonth = now.minus(45, ChronoUnit.DAYS);

        User stillActive = User.builder().id(UUID.randomUUID()).lastActiveAt(inThisMonth).build();
        User churned = User.builder().id(UUID.randomUUID()).lastActiveAt(inLastMonth).build();

        given(userRepository.count()).willReturn(2L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(1L);
        given(userRepository.countGroupByPlan()).willReturn(List.of());
        given(userRepository.findAll()).willReturn(List.of(stillActive, churned));

        MetricsOverviewResponse overview = metricsService.overview();

        // churned user was active in the previous 30-day window, but not in
        // the current one; stillActive was not in the previous window so
        // does NOT count as either churned or "still active from prev".
        assertThat(overview.churn().activeLastMonth()).isEqualTo(1L);
        assertThat(overview.churn().stillActiveThisMonth()).isZero();
        assertThat(overview.churn().churned()).isEqualTo(1L);
    }

    @Test
    void activationFunnel_countsEachStepAcrossMasters() {
        UUID sharedBothWays = UUID.randomUUID();
        given(userRepository.countByRole(Role.USER)).willReturn(5L);
        given(userRepository.countByRoleAndEmailVerifiedTrue(Role.USER)).willReturn(4L);
        given(projectRepository.countDistinctOwners()).willReturn(3L);
        given(estimateRepository.countDistinctProjectOwners()).willReturn(3L);
        given(shareLinkRepository.findSharedOwners()).willReturn(List.of(ownerSource(sharedBothWays, "DIRECT")));
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT))
                .willReturn(List.of(ownerSource(sharedBothWays, "DIRECT"), owner("LIGA")));
        given(estimateRepository.countDistinctProjectOwnersByStatus(EstimateStatus.SIGNED)).willReturn(1L);

        ActivationFunnelResponse f = metricsService.activationFunnel();

        assertThat(f.registered()).isEqualTo(5L);
        assertThat(f.verifiedEmail()).isEqualTo(4L);
        assertThat(f.withProject()).isEqualTo(3L);
        assertThat(f.withEstimate()).isEqualTo(3L);
        // Two masters, not three: the one holding both kinds of link is ONE master. A sum of the two
        // counts would report 3 here and push the step above `withEstimate`, which is impossible.
        assertThat(f.shared()).isEqualTo(2L);
        assertThat(f.withSigned()).isEqualTo(1L);
    }

    @Test
    void activationFunnel_countsAMasterWhoOnlyEverSharedFromTheObject() {
        given(userRepository.countByRole(Role.USER)).willReturn(1L);
        given(userRepository.countByRoleAndEmailVerifiedTrue(Role.USER)).willReturn(1L);
        given(projectRepository.countDistinctOwners()).willReturn(1L);
        given(estimateRepository.countDistinctProjectOwners()).willReturn(1L);
        // The bug this iteration fixes: no per-estimate link at all, and the object portal is the
        // main sharing flow — the step used to read 0 and draw a cliff that was not there.
        given(shareLinkRepository.findSharedOwners()).willReturn(List.of());
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT))
                .willReturn(List.of(owner("DIRECT")));
        given(estimateRepository.countDistinctProjectOwnersByStatus(EstimateStatus.SIGNED)).willReturn(0L);

        assertThat(metricsService.activationFunnel().shared()).isEqualTo(1L);
    }


    @Test
    void bySource_reportsTheWholeFunnelPerSource_notJustRegistrations() {
        // The report used to stop at "activated". A source that brings 25 sign-ups of whom nobody
        // ever signs an estimate looked identical to one that brings 25 who all do.
        given(userRepository.countUsersBySource()).willReturn(List.of(count("LIGA", 25L), count("DIRECT", 8L)));
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of(count("LIGA", 20L), count("DIRECT", 6L)));
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of(count("LIGA", 15L), count("DIRECT", 5L)));
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of(count("LIGA", 12L), count("DIRECT", 5L)));
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED))
                .willReturn(List.of(count("LIGA", 3L), count("DIRECT", 4L)));
        given(shareLinkRepository.findSharedOwners()).willReturn(List.of(owner("LIGA"), owner("DIRECT")));
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT))
                .willReturn(List.of(owner("LIGA"), owner("LIGA")));
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.CLICK))
                .willReturn(List.of(count("LIGA", 7L)));
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(UpgradeEventType.INTEREST))
                .willReturn(List.of(count("LIGA", 2L)));
        noUtm();

        SourceBreakdownResponse report = metricsService.bySource();

        // DIRECT first: 4/8 = 50% beats LIGA's 3/25 = 12%, and both clear the threshold. The old
        // ordering (registrations desc) would have put the worse-converting source on top.
        assertThat(report.sources()).extracting(SourceBreakdownResponse.SourceStat::source)
                .containsExactly("DIRECT", "LIGA");
        SourceBreakdownResponse.SourceStat liga = report.sources().get(1);
        assertThat(liga.registered()).isEqualTo(25L);
        assertThat(liga.verifiedEmail()).isEqualTo(20L);
        assertThat(liga.activated()).isEqualTo(15L);
        assertThat(liga.withEstimate()).isEqualTo(12L);
        assertThat(liga.withSigned()).isEqualTo(3L);
        assertThat(liga.proClicks()).isEqualTo(7L);
        assertThat(liga.proInterested()).isEqualTo(2L);
        assertThat(liga.enoughData()).isTrue();
        assertThat(report.significanceThreshold()).isEqualTo(5);
    }

    @Test
    void bySource_sharedIsTheSAMEUnionTheFunnelUses_notASumOfTwoCounts() {
        // Three rows, two masters: the LIGA master holds both kinds of link. Summing the two
        // queries would report 2 for LIGA and push its `shared` above its `withEstimate`.
        UUID both = UUID.randomUUID();
        given(userRepository.countUsersBySource()).willReturn(List.of(count("LIGA", 1L), count("DIRECT", 1L)));
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of());
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED)).willReturn(List.of());
        given(shareLinkRepository.findSharedOwners())
                .willReturn(List.of(ownerSource(both, "LIGA"), owner("DIRECT")));
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT))
                .willReturn(List.of(ownerSource(both, "LIGA")));
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(any(UpgradeEventType.class)))
                .willReturn(List.of());
        noUtm();

        List<SourceBreakdownResponse.SourceStat> rows = metricsService.bySource().sources();

        assertThat(rows).extracting(SourceBreakdownResponse.SourceStat::shared).containsOnly(1L);
        assertThat(rows).hasSize(2);
    }

    @Test
    void bySource_aSourceBelowTheThresholdIsFlaggedAndNeverSortsOnItsPercentage() {
        // One registration that signed once is 100%. Ranked on that number it would sit above a
        // source with 25 masters forever — which is exactly the wrong reading to hand an admin.
        given(userRepository.countUsersBySource()).willReturn(List.of(count("TIKTOK", 1L), count("LIGA", 25L)));
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of());
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED))
                .willReturn(List.of(count("TIKTOK", 1L), count("LIGA", 3L)));
        given(shareLinkRepository.findSharedOwners()).willReturn(List.of());
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT)).willReturn(List.of());
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(any(UpgradeEventType.class)))
                .willReturn(List.of());
        noUtm();

        List<SourceBreakdownResponse.SourceStat> rows = metricsService.bySource().sources();

        assertThat(rows).extracting(SourceBreakdownResponse.SourceStat::source).containsExactly("LIGA", "TIKTOK");
        assertThat(rows.get(0).enoughData()).isTrue();
        assertThat(rows.get(1).enoughData()).isFalse();
    }

    @Test
    void bySource_theNullUtmBucketSurvivesAsARowOfItsOwn() {
        // "Arrived with no tags" is the largest bucket and a real answer. Dropped from the fold,
        // the UTM table would quietly total less than the registration count.
        given(userRepository.countUsersBySource()).willReturn(List.of());
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of());
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED)).willReturn(List.of());
        given(shareLinkRepository.findSharedOwners()).willReturn(List.of());
        given(projectShareLinkRepository.findSharedOwners(ShareLinkKind.SHARED_WITH_CLIENT)).willReturn(List.of());
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(any(UpgradeEventType.class)))
                .willReturn(List.of());
        given(userRepository.countUsersByUtmSource())
                .willReturn(List.of(count(null, 30L), count("tiktok", 6L)));
        given(estimateRepository.countOwnersByStatusAndUtmSource(EstimateStatus.SIGNED))
                .willReturn(List.of(count("tiktok", 2L)));

        List<SourceBreakdownResponse.UtmStat> utm = metricsService.bySource().utm();

        assertThat(utm).extracting(SourceBreakdownResponse.UtmStat::source).containsExactly(null, "tiktok");
        assertThat(utm.get(0).registered()).isEqualTo(30L);
        assertThat(utm.get(0).withSigned()).isZero();
        assertThat(utm.get(1).enoughData()).isTrue();
    }

    /** The UTM half of the report, empty — the source half is what the test is about. */
    private void noUtm() {
        given(userRepository.countUsersByUtmSource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndUtmSource(EstimateStatus.SIGNED)).willReturn(List.of());
    }

    private static SourceCount count(String source, long cnt) {
        return new SourceCount() {
            @Override public String getSource() { return source; }
            @Override public long getCnt() { return cnt; }
        };
    }

    private static OwnerSource owner(String source) {
        return ownerSource(UUID.randomUUID(), source);
    }

    private static OwnerSource ownerSource(UUID id, String source) {
        return new OwnerSource() {
            @Override public UUID getOwnerId() { return id; }
            @Override public String getSource() { return source; }
        };
    }
    private UserRepository.PlanCount planCount(Plan plan, long total) {
        return new UserRepository.PlanCount() {
            @Override public Plan getPlan() { return plan; }
            @Override public long getTotal() { return total; }
        };
    }
}
