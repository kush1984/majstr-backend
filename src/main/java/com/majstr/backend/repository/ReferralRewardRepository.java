package com.majstr.backend.repository;

import com.majstr.backend.entity.ReferralReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReferralRewardRepository extends JpaRepository<ReferralReward, UUID> {

    /** The idempotency guard: one invited master ever triggers one reward. Checked
     *  before granting; the DB UNIQUE(referred_user_id) is the hard backstop. */
    boolean existsByReferredUserId(UUID referredUserId);

    /** Invited masters who actually paid (one reward per paid invitee) — the
     *  referrer's "оплатили" stat. */
    long countByReferrerId(UUID referrerId);

    /** Total PRO days this referrer has earned across all rewards ("днів зароблено";
     *  0 for rewards recorded against an admin dateless PRO). */
    @Query("SELECT COALESCE(SUM(r.grantedDays), 0) FROM ReferralReward r WHERE r.referrerId = :referrerId")
    long sumGrantedDaysByReferrerId(@Param("referrerId") UUID referrerId);

    /** Total rewards granted across all masters — an admin overview signal. */
    @Query("SELECT COUNT(r) FROM ReferralReward r")
    long countAllRewards();
}
