package com.majstr.backend.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DB-level guarantees Mockito cannot see: the {@code custom_trade_id ⇒ trade = 'OTHER'}
 * invariant on both tables, the {@code is_default = false} pin on estimate templates, the
 * per-master unique index on custom trade names, and — the whole point of the FK design — that
 * deleting a {@code user_trade} row via {@code ON DELETE SET NULL} really does drop referencing
 * positions/templates back to plain OTHER with no application code involved.
 */
class CustomTradeIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private UUID ownerId;

    @BeforeEach
    void seedAnOwner() {
        jdbc.update("DELETE FROM catalog_items");
        jdbc.update("DELETE FROM estimate_templates WHERE is_default = false");
        jdbc.update("DELETE FROM user_trade");
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
    }

    private UUID customTrade(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_trade (id, user_id, name) VALUES (?, ?, ?)", id, ownerId, name);
        return id;
    }

    private UUID catalogItem(String trade, UUID customTradeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, trade, custom_trade_id, type, unit,
                                           default_price, source, sort_order, created_at)
                VALUES (?, ?, 'Позиція', ?, ?, 'WORK', 'PIECE', 100.00, 'MANUAL', 0, NOW())
                """, id, ownerId, trade, customTradeId);
        return id;
    }

    // ---- catalog_items -----------------------------------------------------

    @Test
    void catalogItem_customTradeWithSystemTrade_violatesTheInvariant() {
        UUID custom = customTrade("Натяжні стелі");

        assertThatThrownBy(() -> catalogItem("ELECTRICAL", custom))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void catalogItem_customTradeWithOther_isAccepted() {
        UUID custom = customTrade("Натяжні стелі");

        UUID itemId = catalogItem("OTHER", custom);

        String storedTrade = jdbc.queryForObject(
                "SELECT trade FROM catalog_items WHERE id = ?", String.class, itemId);
        assertThat(storedTrade).isEqualTo("OTHER");
    }

    @Test
    void deletingACustomTrade_dropsItsCatalogItemsBackToPlainOther_viaOnDeleteSetNull() {
        UUID custom = customTrade("Натяжні стелі");
        UUID itemId = catalogItem("OTHER", custom);

        jdbc.update("DELETE FROM user_trade WHERE id = ?", custom);

        UUID storedCustomTradeId = jdbc.queryForObject(
                "SELECT custom_trade_id FROM catalog_items WHERE id = ?", UUID.class, itemId);
        String storedTrade = jdbc.queryForObject(
                "SELECT trade FROM catalog_items WHERE id = ?", String.class, itemId);
        assertThat(storedCustomTradeId).isNull();
        assertThat(storedTrade).isEqualTo("OTHER"); // nothing lost — plain "Інше" now
    }

    // ---- estimate_templates -------------------------------------------------

    @Test
    void estimateTemplate_customTradeOnADefaultTemplate_violatesTheInvariant() {
        UUID custom = customTrade("Натяжні стелі");
        UUID templateId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, custom_trade_id, is_default, created_at, updated_at)
                VALUES (?, NULL, 'Шаблон', 'OTHER', ?, true, NOW(), NOW())
                """, templateId, custom))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void estimateTemplate_customTradeOnAnOwnTemplate_isAccepted() {
        UUID custom = customTrade("Натяжні стелі");
        UUID templateId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, custom_trade_id, is_default, created_at, updated_at)
                VALUES (?, ?, 'Мій шаблон', 'OTHER', ?, false, NOW(), NOW())
                """, templateId, ownerId, custom);

        String storedTrade = jdbc.queryForObject(
                "SELECT trade FROM estimate_templates WHERE id = ?", String.class, templateId);
        assertThat(storedTrade).isEqualTo("OTHER");
    }

    @Test
    void deletingACustomTrade_dropsItsOwnTemplatesBackToPlainOther() {
        UUID custom = customTrade("Натяжні стелі");
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, custom_trade_id, is_default, created_at, updated_at)
                VALUES (?, ?, 'Мій шаблон', 'OTHER', ?, false, NOW(), NOW())
                """, templateId, ownerId, custom);

        jdbc.update("DELETE FROM user_trade WHERE id = ?", custom);

        UUID storedCustomTradeId = jdbc.queryForObject(
                "SELECT custom_trade_id FROM estimate_templates WHERE id = ?", UUID.class, templateId);
        assertThat(storedCustomTradeId).isNull();
    }

    // ---- per-master unique name ---------------------------------------------

    @Test
    void twoCustomTradesWithTheSameNameForOneMaster_violateTheUniqueIndex() {
        customTrade("Натяжні стелі");

        // Different case and surrounding whitespace — the index is on lower(btrim(name)).
        assertThatThrownBy(() -> customTrade("  натяжні стелі  "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameNameIsFineForTwoDifferentMasters() {
        UUID first = customTrade("Натяжні стелі");

        UUID otherOwner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Другий майстер', '+380', 'ФОП', ?)
                """, otherOwner, otherOwner + "@t.ua", otherOwner + "@t.ua",
                otherOwner.toString().substring(0, 8));
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO user_trade (id, user_id, name) VALUES (?, ?, ?)",
                second, otherOwner, "Натяжні стелі");

        assertThat(first).isNotEqualTo(second); // both rows exist — no exception thrown above
    }
}
