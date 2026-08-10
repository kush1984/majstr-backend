package com.majstr.backend.service;

import com.majstr.backend.repository.PriceInsightCandidateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly trigger for {@link PriceInsightService#weeklyRefresh()} — Saturday night, Kyiv time, so
 * it runs when nobody is actively pricing an estimate.
 *
 * <p>A separate bean, deliberately — same reasoning as {@code TrialReminderService} staying out
 * of {@code AutoRenewService}: a bug in a reporting job must never share a class with a path that
 * writes money-relevant data (this job doesn't, but the isolation habit is cheap and worth
 * keeping consistent).
 *
 * <p>Single-node deployment today (see open-questions "Multi-instance support"), but the guard
 * here is a real Postgres advisory lock rather than a comment promising one later — a second
 * instance racing the same cron minute finds the lock held and skips cleanly instead of
 * duplicating (or interleaving) the refresh.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PriceInsightRefreshJob {

    /** Arbitrary but stable. If a second advisory lock is ever added to this project, give it
     *  its own key — nothing today reserves a range. */
    private static final long ADVISORY_LOCK_KEY = 827_100_940_1L;

    private final PriceInsightService priceInsightService;
    private final PriceInsightCandidateRepository candidateRepository;

    @Scheduled(cron = "${app.price-insight.refresh-cron:0 0 2 ? * SAT}", zone = "Europe/Kyiv")
    @Transactional
    public void weeklyRefresh() {
        if (!candidateRepository.tryAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
            log.info("price-insight weekly refresh skipped — another instance already holds the lock");
            return;
        }
        priceInsightService.weeklyRefresh();
    }
}
