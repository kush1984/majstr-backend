package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V175's two re-read coefficients, read back off the applied schema so a later migration cannot
 * quietly re-seed the old figure (the pattern of {@code NormDataCorrectionsOnLiveDataIntegrationTest}).
 */
class AdhesiveAndPlasterNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    /** Ceresit CM 17: ≈5,8 kg/m² at a 12 mm notch + ≈1 kg/m² on the tile — 7,0 up to 1000×1000. */
    @Test
    void aSquareFormatUpToAMetreLaysOnSevenKilograms() {
        for (String position : new String[]{"укладання плитки 600х600", "укладання плитки 800х800",
                "укладання плитки 1000х1000 мм"}) {
            assertThat(qty(position, "TILE_ADHESIVE_C2")).as("%s", position).isEqualByComparingTo("7.0");
        }
    }

    @Test
    void theLargerFormatsKeepEightAndAHalf() {
        for (String position : new String[]{"укладання плитки 600х1200", "укладання плитки 800х1600",
                "укладання плитки 1200х2400 мм", "укладання плитки 1200х1200 мм"}) {
            assertThat(qty(position, "TILE_ADHESIVE_C2")).as("%s", position).isEqualByComparingTo("8.5");
        }
    }

    /** Knauf Rotband: ≈8,5 kg/m² at 10 mm — 0,9 per mm in every position that buys it by hand. */
    @Test
    void handAppliedGypsumPlasterIsNineTenthsPerMillimetre() {
        List<BigDecimal> figures = jdbc.queryForList("""
                SELECT DISTINCT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'PLASTER_GYPSUM'
                """, BigDecimal.class);
        assertThat(figures).singleElement().satisfies(f -> assertThat(f).isEqualByComparingTo("0.9"));
    }

    /** Recorded as checked, not changed: Knauf MP 75 gives 0,85-1,0; Kreisel 1,1-1,4 machine / 1,5-1,7 hand. */
    @Test
    void theMachineGypsumAndBothCementLimeFiguresStand() {
        assertThat(qty("штукатурка стін машинкою до 2см", "PLASTER_GYPSUM_MACHINE")).isEqualByComparingTo("0.95");
        assertThat(qty("штукатурка стін цементно-вапняною сумішшю (санвузли, укоси, фасади)", "PLASTER_CEMENT_LIME"))
                .isEqualByComparingTo("1.4");
        assertThat(qty("штукатурка маякова цементна", "PLASTER_CEMENT_LIME")).isEqualByComparingTo("1.6");
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }
}
