package com.majstr.backend.service;

import com.majstr.backend.repository.PriceInsightCandidateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PriceInsightRefreshJobTest {

    @Mock PriceInsightService priceInsightService;
    @Mock PriceInsightCandidateRepository candidateRepository;
    @InjectMocks PriceInsightRefreshJob job;

    @Test
    void weeklyRefresh_runsWhenTheAdvisoryLockIsAcquired() {
        given(candidateRepository.tryAdvisoryXactLock(anyLong())).willReturn(true);

        job.weeklyRefresh();

        verify(priceInsightService).weeklyRefresh();
    }

    @Test
    void weeklyRefresh_skipsWhenAnotherInstanceAlreadyHoldsTheLock() {
        given(candidateRepository.tryAdvisoryXactLock(anyLong())).willReturn(false);

        job.weeklyRefresh();

        // The whole point of the guard: no double (or interleaved) run on a second instance.
        verify(priceInsightService, never()).weeklyRefresh();
    }
}
