package com.majstr.backend.integration;

import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CatalogTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V224 — the DOORS_WINDOWS and CLIMATE trades and the positions a Lviv-oblast price sheet had that the
 * library did not, read back off the applied schema.
 *
 * <p>A new enum value hides behind inline CHECKs, so the trades are proven by INSERTING them, not by
 * reading the constraint text.</p>
 */
class DoorsWindowsAndClimateTradesIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CatalogTemplateService catalogTemplateService;

    private int version() {
        return jdbc.queryForObject("""
                SELECT added_in_version FROM catalog_templates
                 WHERE trade = 'DOORS_WINDOWS' AND name = 'Встановлення міжкімнатних дверей'""", Integer.class);
    }

    @Test
    void theNewTradesShipTheirOwnPositions_andShareTheRemovalsAtTheirCurrentPrice() {
        assertThat(count("DOORS_WINDOWS")).isEqualTo(30);
        assertThat(count("CLIMATE")).isEqualTo(23);
        assertThat(price("DOORS_WINDOWS", "Монтаж відливів на вікна", "LINEAR_METER"))
                .isEqualByComparingTo(price("BUILDER", "Монтаж відливів на вікна", "LINEAR_METER"));
        assertThat(price("DOORS_WINDOWS", "Демонтаж віконного блоку", "PIECE"))
                .isEqualByComparingTo(price("DEMOLITION", "Демонтаж віконного блоку", "PIECE"));
    }

    @Test
    void pricesAreTheMedians_orTheLvivFigureWhereItWasTheOnlySource() {
        assertThat(price("DOORS_WINDOWS", "Встановлення міжкімнатних дверей", "PIECE")).isEqualByComparingTo("2200");
        assertThat(price("CLIMATE", "Монтаж кондиціонера 7–12 тис. BTU", "PIECE")).isEqualByComparingTo("6800");
        assertThat(price("DOORS_WINDOWS", "Врізка шпінгалета", "PIECE")).isEqualByComparingTo("240");
        // Owner ruling: the market median, though the library's one-brick wall is 380.
        assertThat(price("DEMOLITION", "Демонтаж цегляної стіни в 1,5 цегли", "M2")).isEqualByComparingTo("270");
        assertThat(price("DEMOLITION", "Демонтаж цегляної стіни у 2 цегли", "M2")).isEqualByComparingTo("300");
    }

    @Test
    void establishedNamesAreNotRenamed() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalog_templates
                 WHERE name IN ('Декоративна штукатурка фасаду короїд баранець', 'Укладання шиферу або ондуліна')""",
                Integer.class)).isEqualTo(3);
    }

    /** The CHECKs take both trades, and «Додати нові з довідника» offers a synced fitter all thirty. */
    @Test
    void aMasterCanPickTheTrade_andSeesItsPositionsAsNewFromTheLibrary() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, ?)
                """, id, id + "@test.ua", id + "@test.ua", "V224" + id.toString().substring(0, 6), version() - 1);
        try {
            jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'DOORS_WINDOWS')", id);
            jdbc.update("""
                    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, trade, source)
                    VALUES (?, ?, 'Монтаж кондиціонера 7–12 тис. BTU', 'WORK', 'PIECE', 6800, 'CLIMATE', 'MANUAL')
                    """, UUID.randomUUID(), id);
            User user = userRepository.findWithTradesById(id).orElseThrow();
            assertThat(catalogTemplateService.countNewFromCatalog(user)).isEqualTo(30);
        } finally {
            jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private int count(String trade) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM catalog_templates
                 WHERE trade = ? AND type = 'WORK' AND added_in_version = ?""", Integer.class, trade, version());
    }

    private BigDecimal price(String trade, String name, String unit) {
        return jdbc.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                 WHERE trade = ? AND name = ? AND unit = ?""", BigDecimal.class, trade, name, unit);
    }
}
