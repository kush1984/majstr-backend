package com.majstr.backend.service;

import com.majstr.backend.dto.DashboardMetricsResponse;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Home-screen metrics for the current contractor. Everything is a DB-side
 * aggregate (counts and a single SUM) — no entities are loaded into memory.
 *
 * <p>"This month" is the current calendar month in UTC, matching the existing
 * admin {@code MetricsService}; near a month boundary this can differ from the
 * contractor's local month (acceptable for now — see iteration-fix-b doc).</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectMessageRepository messageRepository;

    @Transactional(readOnly = true)
    public DashboardMetricsResponse metrics(UUID ownerId) {
        Instant monthStart = currentMonthStartUtc();

        // object-status-unification: both counts are now OBJECTS in the derived stage, not a raw
        // ProjectStatus count / a count of SENT ESTIMATES — see ProjectRepository for why the old
        // pendingEstimates (SENT-estimate count) could disagree with the list filter's object count.
        long activeProjects = projectRepository.countInProgressStage(ownerId);
        long pendingObjects = projectRepository.countPendingSignatureStage(ownerId);
        long completedCount = projectRepository.countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(
                ownerId, ProjectStatus.COMPLETED, monthStart);

        BigDecimal completedAmount = estimateRepository.sumLatestEstimateTotalForCompletedSince(ownerId, monthStart);
        completedAmount = (completedAmount == null ? BigDecimal.ZERO : completedAmount)
                .setScale(2, RoundingMode.HALF_UP);

        long unreadQuestions = messageRepository.countByProjectOwnerIdAndReadFalse(ownerId);

        return new DashboardMetricsResponse(
                activeProjects,
                pendingObjects,
                unreadQuestions,
                new DashboardMetricsResponse.CompletedThisMonth(completedCount, completedAmount));
    }

    private static Instant currentMonthStartUtc() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
