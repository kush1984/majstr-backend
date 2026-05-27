package com.majstr.backend.service;

import com.majstr.backend.dto.MetricsOverviewResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks MetricsService metricsService;

    @Test
    void overview_computesPlanDistributionAndConversion() {
        given(userRepository.count()).willReturn(10L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of(
                planCount(Plan.FREE, 7L),
                planCount(Plan.PRO, 2L),
                planCount(Plan.TEAM, 1L)
        ));

        MetricsOverviewResponse overview = metricsService.overview();

        assertThat(overview.usersTotal()).isEqualTo(10L);
        assertThat(overview.planDistribution())
                .containsEntry(Plan.FREE, 7L)
                .containsEntry(Plan.PRO, 2L)
                .containsEntry(Plan.TEAM, 1L);
        // 3 paid (PRO+TEAM) of 10 total = 30.00%
        assertThat(overview.conversionRatePercent()).isEqualByComparingTo("30.00");
    }

    @Test
    void overview_zeroUsersReturnsZeroConversionInsteadOfDivideByZero() {
        given(userRepository.count()).willReturn(0L);
        given(userRepository.countByCreatedAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.countByLastActiveAtAfter(any(Instant.class))).willReturn(0L);
        given(userRepository.findAll()).willReturn(List.of());
        given(userRepository.countGroupByPlan()).willReturn(List.of());

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

    private UserRepository.PlanCount planCount(Plan plan, long total) {
        return new UserRepository.PlanCount() {
            @Override public Plan getPlan() { return plan; }
            @Override public long getTotal() { return total; }
        };
    }
}
