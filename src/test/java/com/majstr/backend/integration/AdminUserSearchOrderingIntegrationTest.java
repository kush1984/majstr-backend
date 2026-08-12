package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin panel's user list ordering — against a real database, because the bug this guards
 * against lived entirely inside the ORDER BY expression and a mocked-repository test could not see it.
 *
 * <p><b>Why this exists.</b> The first cut sorted the "everyone else" bucket by
 * {@code u.lastActiveAt DESC, u.createdAt DESC}. {@code lastActiveAt} is stamped on every
 * authenticated request (throttled to 5 min, {@link com.majstr.backend.security.LastActiveTracker}),
 * so it is non-null for nearly every user who has ever logged in — the bare DESC on it silently
 * outranked {@code createdAt}, so the admin's «Реєстрація» column looked shuffled (e.g. 09.07, 12.08,
 * 20.06 in a row) instead of chronological. {@link UserRepositorySearchTest} could not catch this: it
 * mocks the query away and only checks that parameters are forwarded correctly, never that the SQL
 * string actually orders anything right.</p>
 *
 * <p>{@code @Transactional}: rolled back after the test, AND it is what lets the raw
 * {@code created_at} backdate below run at all — {@code User#onCreate} always stamps
 * {@code createdAt = Instant.now()} on insert, so the only way to get controlled, distinct
 * registration dates is a same-transaction native UPDATE after {@code save()}.</p>
 */
@Transactional
class AdminUserSearchOrderingIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @Test
    void activeUsersStayPinnedFirst_everyoneElseIsStrictlyByRegistrationDate() {
        String tag = "ordtest" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        // Three NOT-active users, registered on three different dates — this is the bucket the bug
        // shuffled.
        User june = save(user(tag, "june", null));
        User july = save(user(tag, "july", null));
        User august = save(user(tag, "august", null));
        // One active-right-now user, registered BEFORE all three above — must still float to the top.
        User onlineNow = save(user(tag, "online", now.minus(1, ChronoUnit.MINUTES)));

        backdateCreatedAt(june, now.minus(60, ChronoUnit.DAYS));
        backdateCreatedAt(july, now.minus(40, ChronoUnit.DAYS));
        backdateCreatedAt(august, now.minus(10, ChronoUnit.DAYS));
        backdateCreatedAt(onlineNow, now.minus(90, ChronoUnit.DAYS));
        entityManager.flush();
        entityManager.clear();

        Instant activeSince = now.minus(15, ChronoUnit.MINUTES);
        Pageable pageable = PageRequest.of(0, 20);

        List<UUID> descOrder = userRepository
                .searchAdmin(null, null, tag, activeSince, pageable)
                .getContent().stream().map(User::getId).toList();
        assertThat(descOrder)
                .as("online first (untouched by registration order), then registration newest→oldest")
                .containsExactly(onlineNow.getId(), august.getId(), july.getId(), june.getId());

        List<UUID> ascOrder = userRepository
                .searchAdmin(null, null, tag, activeSince, true, pageable)
                .getContent().stream().map(User::getId).toList();
        assertThat(ascOrder)
                .as("online STILL first even with the «Реєстрація» toggle set to ascending")
                .containsExactly(onlineNow.getId(), june.getId(), july.getId(), august.getId());
    }

    private void backdateCreatedAt(User user, Instant createdAt) {
        entityManager.createNativeQuery("UPDATE users SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, user.getId())
                .executeUpdate();
    }

    private User save(User user) {
        return userRepository.save(user);
    }

    private User user(String tag, String label, Instant lastActiveAt) {
        String email = tag + "-" + label + "@order.test";
        return User.builder()
                .email(email)
                .emailCanonical(email)
                .passwordHash("x")
                .fullName(tag + " " + label)
                .phone("+380500000000")
                .companyName("ФОП")
                .referralCode(UUID.randomUUID().toString().substring(0, 12))
                .plan(Plan.FREE)
                .lastActiveAt(lastActiveAt)
                .build();
    }
}
