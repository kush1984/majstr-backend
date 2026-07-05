package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily downgrade of lapsed PRO subscriptions to FREE. A subscription is
 * downgraded only once its {@code planExpiresAt} plus a grace window has passed,
 * so a late renewal within grace keeps PRO uninterrupted. Downgrade is <b>soft</b>
 * (matches the product's downgrade principle): the plan flips to FREE — everything
 * the master created stays visible/downloadable, only creating <i>new</i> over the
 * FREE limits is blocked (enforced on create, as today). Single-node like the token
 * cleanup; needs a shared lock (ShedLock) if scaled out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingExpiryService {

    private final UserRepository userRepository;
    private final BillingProperties props;

    @Scheduled(cron = "${app.billing.expiry-cron:0 30 3 * * *}")
    @Transactional
    public void downgradeExpired() {
        Instant cutoff = Instant.now().minus(props.graceDays(), ChronoUnit.DAYS);
        List<User> expired = userRepository.findExpiredSubscriptions(cutoff);
        for (User user : expired) {
            user.setPlan(Plan.FREE);
            user.setPlanExpiresAt(null);
            // Grace exhausted — stop retrying a card that never charged.
            user.setAutoRenew(false);
        }
        if (!expired.isEmpty()) {
            log.info("Downgraded {} expired PRO subscription(s) to FREE", expired.size());
        }
    }
}
