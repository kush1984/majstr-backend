package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.AdminUserSummary;
import com.majstr.backend.entity.AdminPlanGrant;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.AdminPlanGrantRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin's plan change, with a term (V199). A paid plan given for N months ends N calendar
 * months (Kyiv time) after what is left of the SAME plan's current term, or after today; the daily
 * {@link BillingExpiryService} then takes it back to FREE like any other lapsed subscription. No
 * term = no end, as admin grants always were. Every change is recorded with the admin who made it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPlanService {

    private final UserRepository userRepository;
    private final AdminPlanGrantRepository grantRepository;

    @Transactional
    public AdminUserSummary change(UUID userId, Plan plan, Integer months, String actorEmail) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        Instant now = Instant.now();
        Integer term = plan == Plan.FREE ? null : months;
        if (plan != Plan.FREE) {
            user.setPlanExpiresAt(term == null ? null : endOf(user, plan, term, now));
        }
        user.setPlan(plan);
        grantRepository.save(AdminPlanGrant.builder()
                .id(UUID.randomUUID()).userId(userId).actorEmail(actorEmail).plan(plan)
                .months(term).expiresAt(plan == Plan.FREE ? null : user.getPlanExpiresAt())
                .createdAt(now).build());
        log.info("Admin {} set plan {} for {} ({})", actorEmail, plan, userId,
                term == null ? (plan == Plan.FREE ? "free" : "no end") : term + " month(s)");
        return AdminUserSummary.from(user);
    }

    /** Months are ADDED to a running term of the same plan, so «+1 місяць» never shortens one. */
    static Instant endOf(User user, Plan plan, int months, Instant now) {
        Instant current = user.getPlanExpiresAt();
        Instant from = user.getPlan() == plan && current != null && current.isAfter(now) ? current : now;
        return from.atZone(LocalizationConfig.ZONE).plusMonths(months).toInstant();
    }
}
