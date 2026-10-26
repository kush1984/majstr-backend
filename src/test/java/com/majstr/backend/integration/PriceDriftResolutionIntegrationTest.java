package com.majstr.backend.integration;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.CatalogService;
import com.majstr.backend.service.CatalogTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inline editing, stage 3 (V179) — what happens to an open PRICE_DRIFT notice. The notices here are
 * planted with exactly the columns V176's price review writes for its 253 changes, on positions that
 * review moved, so this is the master who opens the app after that deploy.
 *
 * <ul>
 *   <li>he re-prices the position himself → the notice closes as RESOLVED; a rename alone does not;</li>
 *   <li>he taps «Оновити мою ціну» over a price he had already changed → the new price IS applied.</li>
 * </ul>
 */
class PriceDriftResolutionIntegrationTest extends IntegrationTestBase {

    private static final String MESH = "Армування кладки сіткою арматурою";      // V176: 50 → 100
    private static final String FACADE = "Вентильований фасад";                  // V176: 850 → 1150

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogService catalogService;
    @Autowired private CatalogTemplateService catalogTemplateService;

    private UUID master;

    @BeforeEach
    void plantMaster() {
        master = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 1)
                """, master, master + "@test.ua", master + "@test.ua", "PD" + master.toString().substring(0, 8));
    }

    @AfterEach
    void removeMaster() {
        jdbc.update("DELETE FROM catalog_update_notices WHERE user_id = ?", master);
        jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", master);
        jdbc.update("DELETE FROM users WHERE id = ?", master);
    }

    private UUID item(String name, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (?, ?, ?, 'Кладка', 'WORK', 'M2', ?::numeric, 'BUILDER', 'LIBRARY', 0)
                """, id, master, name, price);
        return id;
    }

    /** The row V176 inserts for a master still on our old number. */
    private UUID notice(String name, String oldPrice, String newPrice) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    position_name, old_price, new_price)
                VALUES (?, ?, 'PRICE_DRIFT', 0, 0, ?, ?::numeric, ?::numeric)
                """, id, master, name, oldPrice, newPrice);
        return id;
    }

    private Map<String, Object> noticeRow(UUID id) {
        return jdbc.queryForMap("SELECT dismissed_at, resolution FROM catalog_update_notices WHERE id = ?", id);
    }

    private BigDecimal price(UUID itemId) {
        return jdbc.queryForObject("SELECT default_price FROM catalog_items WHERE id = ?", BigDecimal.class, itemId);
    }

    private CatalogItemRequest request(String name, String price) {
        return new CatalogItemRequest(name, "Кладка", Trade.BUILDER, null, ItemType.WORK, Unit.M2, new BigDecimal(price));
    }

    @Test
    void repricingThePositionHimselfResolvesHisOpenNotice() {
        UUID mesh = item(MESH, "50.00");
        UUID facade = item(FACADE, "850.00");
        UUID meshNotice = notice(MESH, "50.00", "100.00");
        UUID facadeNotice = notice(FACADE, "850.00", "1150.00");

        catalogService.update(mesh, request(MESH, "90"), master);

        assertThat(price(mesh)).isEqualByComparingTo("90");
        assertThat(noticeRow(meshNotice).get("resolution")).isEqualTo("RESOLVED");
        assertThat(noticeRow(meshNotice).get("dismissed_at")).isNotNull();
        // Only the position he re-priced.
        assertThat(noticeRow(facadeNotice).get("dismissed_at")).isNull();
    }

    @Test
    void aRenameWithoutAPriceChangeLeavesTheNoticeOpen() {
        UUID mesh = item(MESH, "50.00");
        UUID meshNotice = notice(MESH, "50.00", "100.00");

        catalogService.update(mesh, request(MESH + " (по швах)", "50"), master);

        assertThat(noticeRow(meshNotice).get("dismissed_at")).isNull();
        assertThat(noticeRow(meshNotice).get("resolution")).isNull();
    }

    @Test
    void acceptingAppliesTheNewPriceEvenOverOneHeChangedHimself() {
        UUID facade = item(FACADE, "990.00"); // he moved it after V176 queued the notice
        UUID facadeNotice = notice(FACADE, "850.00", "1150.00");

        catalogTemplateService.acceptUpdateNotice(master, facadeNotice);

        assertThat(price(facade)).isEqualByComparingTo("1150");
        assertThat(noticeRow(facadeNotice).get("resolution")).isEqualTo("ACCEPTED");
    }

    @Test
    void dismissingRecordsWhy() {
        UUID facadeNotice = notice(FACADE, "850.00", "1150.00");
        item(FACADE, "850.00");

        catalogTemplateService.dismissUpdateNotice(master, facadeNotice);

        assertThat(noticeRow(facadeNotice).get("resolution")).isEqualTo("DISMISSED");
    }
}
