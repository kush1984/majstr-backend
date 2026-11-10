package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.dto.SourceBreakdownResponse;
import com.majstr.backend.dto.SourceCount;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.PdfRender;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.UpgradeEventType;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.PdfRenderRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UpgradeEventRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock com.majstr.backend.repository.ReferralRewardRepository referralRewardRepository;
    @Mock UpgradeEventRepository upgradeEventRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PdfRenderRepository pdfRenderRepository;
    @InjectMocks MetricsService metricsService;

    /** The real handover sets over the mocked queries — the computation under test is shared. */
    @BeforeEach
    void realHandoffs() {
        ReflectionTestUtils.setField(metricsService, "handoffs",
                new ClientHandoffs(estimateRepository, pdfRenderRepository));
    }

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
        UUID sentAndSigned = UUID.randomUUID();
        given(userRepository.countByRole(Role.USER)).willReturn(5L);
        given(userRepository.countByRoleAndEmailVerifiedTrue(Role.USER)).willReturn(4L);
        given(projectRepository.countDistinctOwners()).willReturn(3L);
        given(estimateRepository.countDistinctProjectOwners()).willReturn(3L);
given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED))
                .willReturn(List.of(ownerSource(sentAndSigned, "DIRECT"), owner("LIGA")));
        given(estimateRepository.countDistinctProjectOwnersByStatus(EstimateStatus.SIGNED)).willReturn(1L);
        given(userRepository.countByIdInAndEmailVerifiedFalse(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<UUID> ids) -> ids.size() == 2 && ids.contains(sentAndSigned))))
                .willReturn(1L);

        ActivationFunnelResponse f = metricsService.activationFunnel();

        assertThat(f.registered()).isEqualTo(5L);
        assertThat(f.verifiedEmail()).isEqualTo(4L);
        assertThat(f.withProject()).isEqualTo(3L);
        assertThat(f.withEstimate()).isEqualTo(3L);
        // Owners with a SENT or SIGNED estimate (2026-11-09), not owners of a link row.
        assertThat(f.shared()).isEqualTo(2L);
        assertThat(f.withSigned()).isEqualTo(1L);
        // Asked over the SAME de-duplicated set the `shared` step counts, never a second definition.
        assertThat(f.sharedUnverified()).isEqualTo(1L);
    }

    @Test
    void activationFunnel_handedToClientIsTheUnionOfSharedAndPdf_oneMasterOnce() {
        UUID sharedOnly = UUID.randomUUID();
        UUID both = UUID.randomUUID();
        UUID pdfOnly = UUID.randomUUID();
        given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED))
                .willReturn(List.of(ownerSource(sharedOnly, "DIRECT"), ownerSource(both, "LIGA")));
        given(pdfRenderRepository.findOwnersWithPdf(PdfRender.Reader.MASTER))
                .willReturn(List.of(ownerSource(both, "LIGA"), ownerSource(pdfOnly, "LIGA")));
        given(pdfRenderRepository.findOwnersWithPdf(PdfRender.Reader.CLIENT))
                .willReturn(List.of(ownerSource(sharedOnly, "DIRECT")));

        ActivationFunnelResponse f = metricsService.activationFunnel();

        assertThat(f.shared()).isEqualTo(2L);
        assertThat(f.pdfMade()).isEqualTo(2L);
        // Three masters, not 2 + 2: the one who did both is one master.
        assertThat(f.handedToClient()).isEqualTo(3L);
        assertThat(f.clientDownloadedPdf()).isEqualTo(1L);
    }

    @Test
    void bySource_handedToClientFoldsTheSameUnion_perSource() {
        UUID both = UUID.randomUUID();
        given(userRepository.countUsersBySource()).willReturn(List.of(count("LIGA", 3L), count("DIRECT", 1L)));
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of());
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED)).willReturn(List.of());
        given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED))
                .willReturn(List.of(ownerSource(both, "LIGA"), owner("DIRECT")));
        given(pdfRenderRepository.findOwnersWithPdf(PdfRender.Reader.MASTER))
                .willReturn(List.of(ownerSource(both, "LIGA"), owner("LIGA")));
        given(upgradeEventRepository.countDistinctUsersBySourceAndType(any(UpgradeEventType.class)))
                .willReturn(List.of());
        noUtm();

        List<SourceBreakdownResponse.SourceStat> rows = metricsService.bySource().sources();

        SourceBreakdownResponse.SourceStat liga = rows.stream().filter(r -> "LIGA".equals(r.source())).findFirst().orElseThrow();
        assertThat(liga.shared()).isEqualTo(1L);
        assertThat(liga.pdfMade()).isEqualTo(2L);
        assertThat(liga.handedToClient()).isEqualTo(2L);
        SourceBreakdownResponse.SourceStat direct = rows.stream().filter(r -> "DIRECT".equals(r.source())).findFirst().orElseThrow();
        assertThat(direct.handedToClient()).isEqualTo(1L);
        assertThat(direct.pdfMade()).isZero();
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
given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED))
                .willReturn(List.of(owner("LIGA"), owner("DIRECT"), owner("LIGA")));
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
    void bySource_sharedIsTheSameOwnerSetTheFunnelUses_oneMasterOnce() {
        // The aggregate and the by-source row fold the SAME owner map: a master the query names
        // twice is still one master in his source's row.
        UUID both = UUID.randomUUID();
        given(userRepository.countUsersBySource()).willReturn(List.of(count("LIGA", 1L), count("DIRECT", 1L)));
        given(userRepository.countVerifiedUsersBySource()).willReturn(List.of());
        given(projectRepository.countActivatedOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countEstimateOwnersBySource()).willReturn(List.of());
        given(estimateRepository.countOwnersByStatusAndSource(EstimateStatus.SIGNED)).willReturn(List.of());
given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED))
                .willReturn(List.of(ownerSource(both, "LIGA"), owner("DIRECT"), ownerSource(both, "LIGA")));
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
given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED)).willReturn(List.of());
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
given(estimateRepository.findOwnersWithEstimateIn(SENT_OR_SIGNED)).willReturn(List.of());
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

    private static final List<EstimateStatus> SENT_OR_SIGNED = List.of(EstimateStatus.SENT, EstimateStatus.SIGNED);

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
