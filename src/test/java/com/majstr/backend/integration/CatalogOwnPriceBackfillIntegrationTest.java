package com.majstr.backend.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V225's backfill of {@code catalog_items.own_price} for LIBRARY rows, run as the migration's own SQL
 * (read from the file) against rows planted after it ran. A library row counts as «his» only when its
 * price differs from the same trade's library row AND is not an old library price we told him about in a
 * PRICE_DRIFT notice — a row left at an old price of ours must stay out of the market medians.
 */
class CatalogOwnPriceBackfillIntegrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    private UUID master;
    private Map<String, Object> template;

    @BeforeEach
    void plant() {
        master = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 1)
                """, master, master + "@test.ua", master + "@test.ua", "OP" + master.toString().substring(0, 8));
        template = jdbc.queryForMap("""
                SELECT t.trade, t.name, t.type, t.unit, t.suggested_price FROM catalog_templates t
                WHERE t.type = 'WORK' AND t.unit <> 'PERCENT' AND t.suggested_price > 0
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates o
                                  WHERE o.trade = t.trade AND o.type = t.type AND o.unit = t.unit
                                    AND lower(trim(o.name)) = lower(trim(t.name)) AND o.id <> t.id)
                ORDER BY t.name LIMIT 1
                """);
    }

    @AfterEach
    void remove() {
        jdbc.update("DELETE FROM catalog_update_notices WHERE user_id = ?", master);
        jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", master);
        jdbc.update("DELETE FROM users WHERE id = ?", master);
    }

    private UUID libraryRow(BigDecimal price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, trade, source, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'LIBRARY', 0)
                """, id, master, template.get("name"), template.get("type"), template.get("unit"), price,
                template.get("trade"));
        return id;
    }

    private boolean ownPrice(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT own_price FROM catalog_items WHERE id = ?",
                Boolean.class, id));
    }

    private void runBackfill() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V225__catalog_own_price.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("UPDATE catalog_items ci");
        jdbc.update(sql.substring(start, sql.indexOf(';', start)));
    }

    private BigDecimal libraryPrice() {
        return (BigDecimal) template.get("suggested_price");
    }

    @Test
    void atTheLibraryPrice_itStaysOurs() throws IOException {
        UUID row = libraryRow(libraryPrice());
        runBackfill();
        assertThat(ownPrice(row)).isFalse();
    }

    @Test
    void aPriceHeChanged_isHis() throws IOException {
        UUID row = libraryRow(libraryPrice().add(new BigDecimal("37")));
        runBackfill();
        assertThat(ownPrice(row)).isTrue();
    }

    @Test
    void anOldLibraryPriceWeToldHimAbout_staysOurs() throws IOException {
        BigDecimal old = libraryPrice().subtract(new BigDecimal("1"));
        jdbc.update("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    created_at, position_name, old_price, new_price)
                VALUES (?, ?, 'PRICE_DRIFT', 0, 0, now(), ?, ?, ?)
                """, UUID.randomUUID(), master, template.get("name"), old, libraryPrice());
        UUID row = libraryRow(old);
        runBackfill();
        assertThat(ownPrice(row)).isFalse();
    }
}
