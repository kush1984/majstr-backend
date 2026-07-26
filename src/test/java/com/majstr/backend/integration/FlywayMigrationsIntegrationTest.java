package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations actually run, from an EMPTY database, in order.
 *
 * <p>This is the single highest-value test in the slice. The deploy image is built with
 * {@code -x test} and Hibernate is on {@code ddl-auto: validate}, so a broken migration used
 * to be discovered by the application failing to start in production. Here the context simply
 * refuses to load — in CI, before merge.</p>
 */
class FlywayMigrationsIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyMigrationAppliedCleanly() {
        List<String> failed = jdbc.queryForList(
                "SELECT version || ' ' || description FROM flyway_schema_history WHERE success = false",
                String.class);
        assertThat(failed).isEmpty();

        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        // Not an exact number — that would need editing on every new migration for no signal.
        // The point is that the whole chain ran, not that it is exactly N long.
        assertThat(applied).isNotNull().isGreaterThan(60);
    }

    @Test
    void hibernateSchemaValidationPassed() {
        // Reaching this line already proves it: ddl-auto=validate runs during context startup,
        // so an entity/column mismatch would have failed the context, not this assertion.
        // Kept as an explicit statement of the guarantee, with a cheap sanity probe.
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, Integer.class);
        assertThat(tables).isNotNull().isGreaterThan(15);
    }

    @Test
    void v66RenamedTheTokenColumnsToTokenHash() {
        // Batch B moved verification/reset tokens to hashed-at-rest. If V66 ever gets dropped
        // or reordered, the entities would still compile but every lookup would break.
        assertThat(columnsOf("password_reset_tokens")).contains("token_hash").doesNotContain("token");
        assertThat(columnsOf("email_verification_tokens")).contains("token_hash").doesNotContain("token");
    }

    @Test
    void paymentsPeriodCheckAcceptsTheAnnualTariff() {
        // V65 had to DROP a constraint whose name Postgres generated, then re-add it. If that
        // lookup-based drop silently matched nothing, YEAR would still be rejected here.
        String def = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(con.oid)
                FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE rel.relname = 'payments' AND con.contype = 'c'
                  AND pg_get_constraintdef(con.oid) ILIKE '%period%'
                """, String.class);
        assertThat(def).contains("MONTH").contains("HALF_YEAR").contains("YEAR");
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}
