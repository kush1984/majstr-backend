package com.majstr.backend.integration;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.ProfileTradesRequest;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.CatalogService;
import com.majstr.backend.service.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trade taken off the profile hides the positions we shipped for it, and deletes nothing: his
 * prices stay, a trade put back shows them again, and what he typed himself never disappears.
 */
class CatalogTradeVisibilityIntegrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogService catalogService;
    @Autowired private ProfileService profileService;

    private UUID master;

    @BeforeEach
    void plantMaster() {
        master = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 1)
                """, master, master + "@test.ua", master + "@test.ua", "CTV" + master.toString().substring(0, 8));
    }

    @AfterEach
    void removeMaster() {
        jdbc.update("DELETE FROM catalog_update_notices WHERE user_id = ?", master);
        jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", master);
        jdbc.update("DELETE FROM user_trades WHERE user_id = ?", master);
        jdbc.update("DELETE FROM users WHERE id = ?", master);
    }

    /** A library position only ONE trade ships — {name, type, unit, trade}. */
    private Map<String, Object> onlyOneTradeShips() {
        return jdbc.queryForMap("""
                SELECT min(name) AS name, type, unit, min(trade) AS trade
                FROM catalog_templates
                GROUP BY lower(trim(name)), type, unit
                HAVING count(DISTINCT trade) = 1
                ORDER BY 1 LIMIT 1
                """);
    }

    /** A library position two trades ship — {name, type, unit, a, b}. */
    private Map<String, Object> twoTradesShip() {
        return jdbc.queryForMap("""
                SELECT min(name) AS name, type, unit, min(trade) AS a, max(trade) AS b
                FROM catalog_templates
                GROUP BY lower(trim(name)), type, unit
                HAVING count(DISTINCT trade) > 1
                ORDER BY 1 LIMIT 1
                """);
    }

    private UUID item(String name, Object type, Object unit, Object trade, String source, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (?, ?, ?, 'Кат', ?, ?, ?::numeric, ?, ?, 0)
                """, id, master, name, type, unit, price, trade, source);
        return id;
    }

    private void trades(String... trades) {
        jdbc.update("DELETE FROM user_trades WHERE user_id = ?", master);
        for (String t : trades) {
            jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, ?)", master, t);
        }
    }

    private CatalogItemResponse listed(UUID id) {
        return catalogService.listForOwner(master, null).stream()
                .filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aShippedPositionIsHiddenWithItsTrade_andShownAgainWithItsPriceWhenTheTradeIsBack() {
        Map<String, Object> p = onlyOneTradeShips();
        String trade = (String) p.get("trade");
        String other = Trade.valueOf(trade) == Trade.PAINTER ? "TILING" : "PAINTER";
        UUID id = item((String) p.get("name"), p.get("type"), p.get("unit"), trade, "LIBRARY", "777.00");

        trades(trade);
        assertThat(listed(id).hidden()).isFalse();

        profileService.updateTrades(master, new ProfileTradesRequest(Set.of(Trade.valueOf(other))));
        assertThat(listed(id).hidden()).isTrue();

        profileService.updateTrades(master, new ProfileTradesRequest(Set.of(Trade.valueOf(trade))));
        CatalogItemResponse back = listed(id);
        assertThat(back.hidden()).isFalse();
        assertThat(back.defaultPrice()).isEqualByComparingTo("777.00");
        Integer copies = jdbc.queryForObject("""
                SELECT count(*) FROM catalog_items
                WHERE owner_id = ? AND lower(trim(name)) = lower(trim(?)) AND type = ? AND unit = ?
                """, Integer.class, master, p.get("name"), p.get("type"), p.get("unit"));
        assertThat(copies).isEqualTo(1);
    }

    @Test
    void whatHeTypedOrImportedNeverHides() {
        Map<String, Object> p = onlyOneTradeShips();
        UUID typed = item("Моя власна позиція", "WORK", "M2", p.get("trade"), "MANUAL", "10.00");
        UUID imported = item("Позиція з прайсу", "WORK", "M2", p.get("trade"), "IMPORT", "10.00");

        trades();

        assertThat(listed(typed).hidden()).isFalse();
        assertThat(listed(imported).hidden()).isFalse();
    }

    @Test
    void oneRowServesTwoTrades_soItStaysWhileEitherIsHis() {
        Map<String, Object> p = twoTradesShip();
        UUID id = item((String) p.get("name"), p.get("type"), p.get("unit"), p.get("a"), "LIBRARY", "50.00");

        trades((String) p.get("b"));

        assertThat(listed(id).hidden()).isFalse();
    }

    @Test
    void theSearchLeavesHiddenPositionsOut() {
        Map<String, Object> p = onlyOneTradeShips();
        item("Тестова прихована зачистка", "WORK", "M2", p.get("trade"), "LIBRARY", "10.00");
        UUID typed = item("Тестова власна зачистка", "WORK", "M2", p.get("trade"), "MANUAL", "10.00");
        trades();

        assertThat(catalogService.search(master, "тестова", null, 10))
                .extracting(CatalogItemResponse::id).containsExactly(typed);
    }

    @Test
    void typingAHiddenPositionAgainMakesItHis_andItShows() {
        Map<String, Object> p = onlyOneTradeShips();
        UUID id = item((String) p.get("name"), p.get("type"), p.get("unit"), p.get("trade"), "LIBRARY", "10.00");
        trades();

        CatalogItemResponse saved = catalogService.create(new CatalogItemRequest((String) p.get("name"), "Кат",
                null, null, ItemType.valueOf((String) p.get("type")), Unit.valueOf((String) p.get("unit")),
                new BigDecimal("25.00")), master);

        assertThat(saved.id()).isEqualTo(id);
        assertThat(listed(id).hidden()).isFalse();
        assertThat(listed(id).defaultPrice()).isEqualByComparingTo("25.00");
    }
}
