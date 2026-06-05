package com.majstr.backend.service;

import com.majstr.backend.repository.EmailVerificationTokenRepository;
import com.majstr.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Daily sweep of dead auth tokens so the tables don't grow unbounded. Refresh
 * tokens are removed once expired or revoked (rotation leaves a revoked row on
 * every use); email-verification tokens once expired. Single-node is fine —
 * if this ever runs on several instances, guard with a shared lock (ShedLock).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;

    @Scheduled(cron = "${app.cleanup.tokens-cron:0 0 3 * * *}")
    @Transactional
    public void purgeDeadTokens() {
        Instant now = Instant.now();
        int refresh = refreshTokenRepository.deleteExpiredOrRevoked(now);
        int verification = verificationTokenRepository.deleteExpired(now);
        if (refresh > 0 || verification > 0) {
            log.info("Token cleanup removed {} refresh + {} email-verification tokens", refresh, verification);
        }
    }
}
