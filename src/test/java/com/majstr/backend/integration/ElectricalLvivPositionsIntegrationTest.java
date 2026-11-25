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
 * V216 — 19 ELECTRICAL positions from a Lviv contractor's estimate, read back off the applied schema.
 *
 * <p>V176's shape: one new catalog version, nothing pushed, offered by «Додати нові з довідника».</p>
 */
class ElectricalLvivPositionsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CatalogTemplateService catalogTemplateService;

    private int version() {
        return jdbc.queryForObject("""
                SELECT added_in_version FROM catalog_templates
                 WHERE trade = 'ELECTRICAL' AND name = 'Монтаж кришки кабельного лотка'""", Integer.class);
    }

    @Test
    void nineteenElectricalPositionsArriveUnderOneVersion_pricedAtApartmentLevel() {
        int version = version();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalog_templates
                 WHERE trade = 'ELECTRICAL' AND type = 'WORK' AND added_in_version = ?""",
                Integer.class, version)).isEqualTo(19);
        assertThat(price("Прокладка силового кабелю перерізом 16–35 мм²", "LINEAR_METER")).isEqualByComparingTo("130");
        assertThat(price("Підключення жили кабелю до затискача, 50–95 мм²", "PIECE")).isEqualByComparingTo("300");
        assertThat(price("Виконавча документація: схеми і протоколи вимірювань", "SET")).isEqualByComparingTo("4000");
    }

    @Test
    void theyJoinTheTradesExistingCategories() {
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT n.category FROM catalog_templates n
                 WHERE n.trade = 'ELECTRICAL' AND n.added_in_version = ?
                   AND NOT EXISTS (SELECT 1 FROM catalog_templates o
                                    WHERE o.trade = 'ELECTRICAL' AND o.category = n.category
                                      AND o.added_in_version < n.added_in_version)""",
                String.class, version())).isEmpty();
    }

    @Test
    void aNameThatCouldBeMistakenSaysWhichPositionIsWhich() {
        assertThat(jdbc.queryForObject("""
                SELECT description FROM catalog_templates
                 WHERE trade = 'ELECTRICAL' AND name = 'Монтаж кабельного лотка шириною від 400 мм'""",
                String.class)).contains("«Монтаж кабельного лотка»");
    }

    /** «Додати нові з довідника» offers a synced ELECTRICAL master exactly these nineteen. */
    @Test
    void aSyncedElectricianSeesThemAsNewFromTheLibrary() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, ?)
                """, id, id + "@test.ua", id + "@test.ua", "V216" + id.toString().substring(0, 6), version() - 1);
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'ELECTRICAL')", id);
        try {
            User user = userRepository.findWithTradesById(id).orElseThrow();
            // Plus whatever a later migration added to ELECTRICAL: «new» is everything above his version.
            assertThat(catalogTemplateService.countNewFromCatalog(user)).isEqualTo(19 + addedLater(version()));
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private int addedLater(int version) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM catalog_templates
                 WHERE trade = 'ELECTRICAL' AND type = 'WORK' AND added_in_version > ?""", Integer.class, version);
    }

    private BigDecimal price(String name, String unit) {
        return jdbc.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                 WHERE trade = 'ELECTRICAL' AND name = ? AND unit = ?""", BigDecimal.class, name, unit);
    }
}
