package com.majstr.backend.service;

import com.majstr.backend.dto.DashboardMetricsResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.repository.EstimateQuestionRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock ProjectRepository projectRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock EstimateQuestionRepository questionRepository;
    @InjectMocks DashboardService dashboardService;

    private final UUID ownerId = UUID.randomUUID();

    @Test
    void metrics_composesCountsAndSum() {
        given(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.IN_PROGRESS)).willReturn(3L);
        given(estimateRepository.countByProjectOwnerIdAndStatus(ownerId, EstimateStatus.SENT)).willReturn(5L);
        given(projectRepository.countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(
                eq(ownerId), eq(ProjectStatus.COMPLETED), any(Instant.class))).willReturn(2L);
        given(estimateRepository.sumLatestEstimateTotalForCompletedSince(eq(ownerId), any(Instant.class)))
                .willReturn(new BigDecimal("12345.5"));
        given(questionRepository.countByEstimateProjectOwnerIdAndReadFalse(ownerId)).willReturn(4L);

        DashboardMetricsResponse r = dashboardService.metrics(ownerId);

        assertThat(r.activeProjects()).isEqualTo(3);
        assertThat(r.pendingEstimates()).isEqualTo(5);
        assertThat(r.unreadQuestions()).isEqualTo(4);
        assertThat(r.completedThisMonth().count()).isEqualTo(2);
        assertThat(r.completedThisMonth().totalAmount()).isEqualByComparingTo("12345.50");
    }

    @Test
    void metrics_nullSumBecomesZero() {
        given(projectRepository.countByOwnerIdAndStatus(ownerId, ProjectStatus.IN_PROGRESS)).willReturn(0L);
        given(estimateRepository.countByProjectOwnerIdAndStatus(ownerId, EstimateStatus.SENT)).willReturn(0L);
        given(projectRepository.countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(
                eq(ownerId), eq(ProjectStatus.COMPLETED), any(Instant.class))).willReturn(0L);
        given(estimateRepository.sumLatestEstimateTotalForCompletedSince(eq(ownerId), any(Instant.class)))
                .willReturn(null);

        DashboardMetricsResponse r = dashboardService.metrics(ownerId);

        assertThat(r.completedThisMonth().totalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void metrics_usesFirstDayOfCurrentMonthUtc() {
        given(projectRepository.countByOwnerIdAndStatus(any(), any())).willReturn(0L);
        given(estimateRepository.countByProjectOwnerIdAndStatus(any(), any())).willReturn(0L);
        given(projectRepository.countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(any(), any(), any())).willReturn(0L);
        given(estimateRepository.sumLatestEstimateTotalForCompletedSince(any(), any())).willReturn(BigDecimal.ZERO);

        dashboardService.metrics(ownerId);

        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(projectRepository).countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(
                eq(ownerId), eq(ProjectStatus.COMPLETED), cap.capture());
        LocalDateTime monthStart = LocalDateTime.ofInstant(cap.getValue(), ZoneOffset.UTC);
        assertThat(monthStart.getDayOfMonth()).isEqualTo(1);
        assertThat(monthStart.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
    }
}
