package com.majstr.backend.integration;

import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.service.NameKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A norm is keyed by NAME and UNIT — this is where that key is exercised against a real schema:
 * the normalisation it goes through, the unique constraints that keep the shipped set and a master's
 * fork apart, and the fact that a per-м.п. norm is simply not found for a per-m² line.
 *
 * <p><b>The trade is NOT part of the key, and these queries do not decide anything by it.</b>
 * {@link MaterialNormRepository#findByKey} answers for the name and unit whatever trade filed the
 * norm; which of those answers may be USED for a given position is decided in
 * {@code MaterialCalculatorService#normsFor} — the position's own trade, or no trade on the norm at
 * all. An earlier draft let the repository's broad answer through unfiltered and put a painter's
 * шпаклівка on a drywall estimate's buying list. So a broad result below is the query working as
 * intended, not the engine's behaviour.</p>
 *
 * <p>{@code estimate_items.trade} is nullable by design (V125 — an ADDENDUM line, or a hand-typed
 * one that matched nothing in the master's catalog), which is why the trade can never be the key.</p>
 */
class MaterialNormLookupIntegrationTest extends IntegrationTestBase {

    private static final String POSITION = "Шпаклювання стін під фарбування V126";

    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialNormRepository normRepository;

    private UUID puttyId;
    private String nameKey;
    private UUID ownerId;

    @BeforeEach
    void seed() {
        nameKey = NameKeys.of(POSITION);
        puttyId = material("Шпаклівка фінішна V126", "25 кг");
        jdbc.update("DELETE FROM material_norm WHERE name_key = ?", nameKey);
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua",
                ownerId.toString().substring(0, 8));
    }

    /**
     * The container's schema is shared with every other integration test, and a leftover DRYWALL
     * norm for a position no catalog ships reads as an orphaned norm to the tests that check the
     * seeded ones against the live catalog.
     */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM material_norm WHERE name_key = ?", nameKey);
        jdbc.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    private UUID material(String name, String spec) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO material (id, name, spec, unit, package_size, package_unit, package_name)
                VALUES (?, ?, ?, 'KG', 25, 'KG', 'мішок')
                ON CONFLICT DO NOTHING
                """, id, name, spec);
        return jdbc.queryForObject("SELECT id FROM material WHERE name = ? AND spec = ?",
                UUID.class, name, spec);
    }

    private UUID norm(Trade trade, String key, Unit unit, String qtyPerUnit) {
        return norm(null, trade, key, unit, qtyPerUnit);
    }

    private UUID norm(UUID owner, Trade trade, String key, Unit unit, String qtyPerUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id,
                                           qty_per_unit, waste_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, 10)
                """, id, owner, trade == null ? null : trade.name(), key, unit.name(), puttyId,
                qtyPerUnit);
        return id;
    }

    @Test
    void aLineWithNoTradeAtAllStillFindsItsNorm() {
        norm(Trade.DRYWALL, nameKey, Unit.M2, "1.2");

        // A V125 line whose trade is NULL has nothing to disagree with, so every candidate stands.
        List<MaterialNorm> found = normRepository.findByKey(nameKey, Unit.M2);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getQtyPerUnit()).isEqualByComparingTo("1.2");
    }

    /**
     * The key ignores the trade, and the SERVICE is what filters on it. Both halves are asserted
     * here, because the broad answer below used to be taken as the engine's answer — which is how a
     * painter's position came to buy drywall materials.
     */
    @Test
    void theKeyIgnoresTheTradeAndTheServiceIsWhatRefusesIt() {
        norm(Trade.DRYWALL, nameKey, Unit.M2, "1.2");

        // The V118 case: the position is filed under PAINTER for this master, the norm under DRYWALL.
        assertThat(normRepository.findByTradeAndKey(Trade.PAINTER, nameKey, Unit.M2)).isEmpty();
        assertThat(normRepository.findByKey(nameKey, Unit.M2)).hasSize(1);
    }

    @Test
    void aTradeSpecificNormIsFoundByItsOwnTradeAndAGeneralOneByAnyone() {
        norm(null, nameKey, Unit.M2, "1.0");
        norm(Trade.DRYWALL, nameKey, Unit.M2, "1.4");

        assertThat(normRepository.findByTradeAndKey(Trade.DRYWALL, nameKey, Unit.M2))
                .singleElement()
                .satisfies(n -> assertThat(n.getQtyPerUnit()).isEqualByComparingTo("1.4"));
        // The key sees both. Which of them may answer for a position is the service's decision: a
        // DRYWALL line takes either, a PAINTER line takes only the one with no trade of its own.
        assertThat(normRepository.findByKey(nameKey, Unit.M2)).hasSize(2);
    }

    @Test
    void theUnitIsThePositionsUnitSoAPerMetreNormIsNotFoundForAPerSquareMetreLine() {
        norm(Trade.DRYWALL, nameKey, Unit.LINEAR_METER, "0.4");

        assertThat(normRepository.findByKey(nameKey, Unit.M2)).isEmpty();
        assertThat(normRepository.findByKey(nameKey, Unit.LINEAR_METER)).hasSize(1);
    }

    @Test
    void twoDefaultNormsForTheSamePositionAndMaterialAreRejected() {
        norm(null, nameKey, Unit.M2, "1.0");

        // owner_id and trade are both NULL here — without NULLS NOT DISTINCT the unique key would
        // not fire on exactly the rows the shipped defaults are made of.
        assertThatThrownBy(() -> norm(null, nameKey, Unit.M2, "1.3"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * «Моя норма, назавжди» rests on this: V126 already put {@code owner_id} INSIDE the natural key,
     * so a master's fork sits beside the default it hides and no migration was needed to allow it.
     * Which of the two wins is decided in Java, on the read path.
     */
    @Test
    void aMastersOwnNormCoexistsWithTheDefaultItWasForkedFrom() {
        norm(Trade.DRYWALL, nameKey, Unit.M2, "1.0");
        norm(ownerId, Trade.DRYWALL, nameKey, Unit.M2, "1.2");

        assertThat(normRepository.findAllByNameKeysForOwner(List.of(nameKey), ownerId)).hasSize(2);
        // Another master sees only the shipped one — a correction is personal, not a catalog edit.
        assertThat(normRepository.findAllByNameKeysForOwner(List.of(nameKey), UUID.randomUUID()))
                .singleElement()
                .satisfies(n -> assertThat(n.getQtyPerUnit()).isEqualByComparingTo("1.0"));
        assertThat(normRepository.findByOwnerIdAndNameKeyAndUnit(ownerId, nameKey, Unit.M2))
                .singleElement()
                .satisfies(n -> assertThat(n.getQtyPerUnit()).isEqualByComparingTo("1.2"));
    }

    /** One coefficient per master per norm — a second fork would make the read path pick a winner. */
    @Test
    void twoOwnNormsForTheSamePositionAndMaterialAreRejected() {
        norm(ownerId, Trade.DRYWALL, nameKey, Unit.M2, "1.2");

        assertThatThrownBy(() -> norm(ownerId, Trade.DRYWALL, nameKey, Unit.M2, "1.4"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNormIsKeyedByTheSameNormalisationTheCatalogPriceResolvesThrough() {
        norm(Trade.DRYWALL, nameKey, Unit.M2, "1.2");

        // Whitespace and case are exactly what NameKeys.of exists to absorb; a second private
        // notion of "the same name" here would drift away from the template price lookup.
        assertThat(NameKeys.of("  ШПАКЛЮВАННЯ   стін під фарбування V126 ")).isEqualTo(nameKey);
        assertThat(normRepository.findAllByNameKeysForOwner(List.of(nameKey), UUID.randomUUID()))
                .hasSize(1);
    }
}
