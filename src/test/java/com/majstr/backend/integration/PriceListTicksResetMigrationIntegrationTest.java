package com.majstr.backend.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V208: every tick 1.79.0 saved by its pre-tick rule is cleared, so the price list picker opens empty
 * until the master ticks it himself. A second database migrated to V207, seeded, then upgraded — the
 * pattern of {@link PaymentReceiptMigrationOnLiveDataIntegrationTest}.
 */
class PriceListTicksResetMigrationIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v208";
    private static final String MASTER = "eeeeeeee-0000-0000-0000-000000000208";
    private static final String TICKED = "eeeeeeee-0000-0000-0000-000000000001";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV207_seedATick_thenUpgrade() throws SQLException {
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + DB;
        db = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("207"))
                .load().migrate();

        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'price-list@test.ua', 'price-list@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'PL208')
                """.formatted(MASTER));
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, trade, type, unit, default_price, source,
                                           sort_order, in_price_list, created_at)
                VALUES ('%s', '%s', 'Укладання плитки', 'TILING', 'WORK', 'M2', 650, 'MANUAL', 0, TRUE, now())
                """.formatted(TICKED, MASTER));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    @Test
    void aTickSavedBeforeV208IsCleared() {
        assertThat(db.queryForObject("SELECT in_price_list FROM catalog_items WHERE id = ?::uuid",
                Boolean.class, TICKED)).isFalse();
    }
}
