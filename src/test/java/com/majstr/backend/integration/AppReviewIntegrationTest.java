package com.majstr.backend.integration;

import com.majstr.backend.dto.AppReviewRequest;
import com.majstr.backend.dto.PublicAppReview;
import com.majstr.backend.entity.AppReviewSource;
import com.majstr.backend.entity.AppReviewStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.AppReviewNotPublishableException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AppReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V218 — masters rate Majstr: when the home screen asks, what is kept, what reaches majstr.pro. */
class AppReviewIntegrationTest extends IntegrationTestBase {

    @Autowired AppReviewService service;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        User user = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x")
                .fullName("Олена Коваль").plan(Plan.FREE).role(Role.USER)
                .trades(new java.util.LinkedHashSet<>(Set.of(Trade.TILING)))
                .referralCode(unique.substring(0, 10))
                .build());
        userId = user.getId();
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, status) VALUES (?, ?, 'Ванна', 'IN_PROGRESS')",
                projectId, userId);
    }

    private void joinedDaysAgo(int days) {
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), userId);
    }

    private void estimate(String status) {
        jdbc.update("INSERT INTO estimates (id, project_id, status) VALUES (?, ?, ?)",
                UUID.randomUUID(), projectId, status);
    }

    @Test
    void theHomeScreenAsksOnlyAfterASuccess_notInTheFirstWeek_andNotAgainSoon() {
        joinedDaysAgo(30);
        estimate("DRAFT");
        assertThat(service.prompt(userId).ask()).as("a draft is not a success yet").isFalse();

        estimate("SENT");
        assertThat(service.prompt(userId).ask()).isTrue();

        joinedDaysAgo(3);
        assertThat(service.prompt(userId).ask()).as("first week").isFalse();

        joinedDaysAgo(30);
        service.dismissPrompt(userId);
        assertThat(service.prompt(userId).ask()).as("«Не зараз» holds for a while").isFalse();

        jdbc.update("UPDATE users SET review_prompt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)), userId);
        assertThat(service.prompt(userId).ask()).as("asked again after the pause").isTrue();
    }

    @Test
    void aRatingIsKept_aLowOneNeverCarriesConsent_andAnswersThePrompt() {
        joinedDaysAgo(30);
        estimate("SIGNED");

        service.submit(userId, new AppReviewRequest(2, "  Не зрозумів акти  ", AppReviewSource.PROMPT, true));

        var row = jdbc.queryForMap("SELECT rating, text, source, publish_consent, status FROM app_review WHERE user_id = ?",
                userId);
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(2);
        assertThat(row.get("text")).isEqualTo("Не зрозумів акти");
        assertThat(row.get("source")).isEqualTo("PROMPT");
        assertThat(row.get("publish_consent")).as("a low rating is never shown").isEqualTo(false);
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(service.prompt(userId).ask()).as("answered = not asked again soon").isFalse();
    }

    @Test
    void onlyAConsentedReviewIsPublished_andTheLandingSeesTheFirstNameAndRealFigures() {
        joinedDaysAgo(65);
        estimate("SIGNED");
        estimate("SENT");
        service.submit(userId, new AppReviewRequest(5, "Кошторис за 10 хвилин", null, true));
        service.submit(userId, new AppReviewRequest(5, "Без згоди", null, false));
        var ids = jdbc.queryForList("SELECT id FROM app_review WHERE user_id = ? ORDER BY text", UUID.class, userId);
        UUID noConsent = ids.get(0);
        UUID consented = ids.get(1);

        assertThat(service.published().stream().filter(p -> p.text() != null && p.text().startsWith("Кошторис")))
                .as("NEW is not on the landing").isEmpty();
        assertThatThrownBy(() -> service.setStatus(noConsent, AppReviewStatus.PUBLISHED))
                .isInstanceOf(AppReviewNotPublishableException.class);

        service.setStatus(consented, AppReviewStatus.PUBLISHED);

        PublicAppReview shown = service.published().stream()
                .filter(p -> "Кошторис за 10 хвилин".equals(p.text())).findFirst().orElseThrow();
        assertThat(shown.authorName()).isEqualTo("Олена К.");
        assertThat(shown.trades()).containsExactly("Плитка");
        assertThat(shown.rating()).isEqualTo(5);
        assertThat(shown.monthsInMajstr()).isEqualTo(2);
        assertThat(shown.estimates()).isEqualTo(2);
        assertThat(shown.signedEstimates()).isEqualTo(1);

        service.setStatus(consented, AppReviewStatus.HIDDEN);
        assertThat(service.published().stream().filter(p -> "Кошторис за 10 хвилин".equals(p.text()))).isEmpty();
    }

    @Test
    void theDatabaseRefusesAPublishedReviewWithoutConsent() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app_review (id, user_id, rating, source, publish_consent, status, published_at)
                VALUES (?, ?, 5, 'PROFILE', false, 'PUBLISHED', now())""", UUID.randomUUID(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app_review (id, user_id, rating, source, publish_consent, status)
                VALUES (?, ?, 6, 'PROFILE', false, 'NEW')""", UUID.randomUUID(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
