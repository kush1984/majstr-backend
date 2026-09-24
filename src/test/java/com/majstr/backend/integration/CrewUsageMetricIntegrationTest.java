package com.majstr.backend.integration;

import com.majstr.backend.dto.CrewUsageResponse;
import com.majstr.backend.service.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Скільки майстрів працює з бригадою» — the admin figure that replaces an assumption.
 *
 * <p>An integration test because the whole thing is one SQL aggregate over a self-referencing
 * estimate table with a {@code role = USER} filter and two optional predicates. Mockito would
 * return whatever the stub was told.</p>
 *
 * <p>The Testcontainers database is shared across the run, so nothing here asserts an absolute
 * count: each test snapshots, adds a known fixture, and asserts the DELTA.</p>
 */
class CrewUsageMetricIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired MetricsService metricsService;

    private UUID master(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, role)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, ?)
                """, id, id + "@t.ua", id + "@t.ua", id.toString().substring(0, 8), role);
        return id;
    }

    private UUID project(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, id, ownerId);
        return id;
    }

    private void copyWithMarkup(UUID projectId, BigDecimal percent, String status, int daysAgo) {
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status, markup_percent, created_at)
                VALUES (?, ?, 'Клієнту', ?, ?, now() - make_interval(days => ?))
                """, UUID.randomUUID(), projectId, status, percent, daysAgo);
    }

    @Test
    void aMasterWithTwoMarkupCopiesIsCountedOnce() {
        CrewUsageResponse before = metricsService.crewUsage();
        UUID ownerId = master("USER");
        UUID projectId = project(ownerId);
        copyWithMarkup(projectId, new BigDecimal("20"), "DRAFT", 1);
        copyWithMarkup(projectId, new BigDecimal("15"), "DRAFT", 2);

        CrewUsageResponse after = metricsService.crewUsage();

        assertThat(after.withMarkupCopy() - before.withMarkupCopy()).isEqualTo(1);
        assertThat(after.withMarkupCopy30d() - before.withMarkupCopy30d()).isEqualTo(1);
        // Nothing signed yet — the workflow was tried, not completed.
        assertThat(after.withSignedMarkupCopy() - before.withSignedMarkupCopy()).isZero();
    }

    /** A DISCOUNT copy is a cheaper offer to the client, not a crew sheet — it is not this. */
    @Test
    void aDiscountCopyIsNotCrewUsage() {
        CrewUsageResponse before = metricsService.crewUsage();
        copyWithMarkup(project(master("USER")), new BigDecimal("-15"), "DRAFT", 1);

        assertThat(metricsService.crewUsage().withMarkupCopy() - before.withMarkupCopy()).isZero();
    }

    @Test
    void theThirtyDayWindowAndTheSignedStepSeparateTheGroups() {
        CrewUsageResponse before = metricsService.crewUsage();
        copyWithMarkup(project(master("USER")), new BigDecimal("20"), "SIGNED", 200);

        CrewUsageResponse after = metricsService.crewUsage();

        assertThat(after.withMarkupCopy() - before.withMarkupCopy()).isEqualTo(1);
        assertThat(after.withMarkupCopy30d() - before.withMarkupCopy30d()).as("200 days old").isZero();
        assertThat(after.withSignedMarkupCopy() - before.withSignedMarkupCopy()).isEqualTo(1);
    }

    /** Every funnel step filters {@code role = USER}; an admin's demo data must not appear in one
     *  report and be missing from the one beside it. */
    @Test
    void anAdminsOwnDemoCopyIsNotCounted() {
        CrewUsageResponse before = metricsService.crewUsage();
        copyWithMarkup(project(master("ADMIN")), new BigDecimal("20"), "DRAFT", 1);

        assertThat(metricsService.crewUsage().withMarkupCopy() - before.withMarkupCopy()).isZero();
    }
}
