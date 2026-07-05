package com.majstr.backend.service;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.dto.ReferralStatsResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ReferralRewardRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only "Запроси майстра" stats for a master's profile: invited count (masters
 * registered with their code), paid count (invitees whose first payment granted a
 * reward), and months earned (granted PRO days ÷ the reward's day length).
 */
@Service
@RequiredArgsConstructor
public class ReferralQueryService {

    private final UserRepository userRepository;
    private final ReferralRewardRepository referralRewardRepository;
    private final BillingProperties billingProperties;

    @Transactional(readOnly = true)
    public ReferralStatsResponse stats(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        long invited = userRepository.countByReferredByUserId(userId);
        long paid = referralRewardRepository.countByReferrerId(userId);
        long daysEarned = referralRewardRepository.sumGrantedDaysByReferrerId(userId);
        int rewardDays = Math.max(1, billingProperties.referralRewardDays());
        return new ReferralStatsResponse(user.getReferralCode(), invited, paid, daysEarned / rewardDays);
    }
}
