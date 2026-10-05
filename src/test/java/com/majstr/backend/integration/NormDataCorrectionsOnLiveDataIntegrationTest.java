package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V145's corrections, read back off the applied schema (review round 2 §3 and B-51).
 *
 * <p>A data-only migration proves itself at apply time — the Testcontainers boot runs it against the
 * real schema and its own {@code DO $$} checks. What that cannot do is stop a LATER migration from
 * quietly undoing it, which is what every assertion here is for: the corrected figure is read from
 * the live database, so re-seeding the old one reddens the build.</p>
 *
 * <p>The §3 corrections all have the same shape — a figure that was right for one geometry and was
 * applied to every geometry — so they are grouped by material rather than listed one per test.</p>
 */
class NormDataCorrectionsOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    /**
     * No C1 sheet in the survey allows a format this size: CM 11 and Kreisel 101/102/111 cap at
     * 40×40, CM 11 Plus/Pro, CM 12 and P-12 at 60×60. «Дошка до 900 мм» also broke V137's own
     * «longer side ≥ 40 cm → 12 mm notch» rule, which is how it was found.
     */
    @Test
    void everyLargeFormatLaysOnC2() {
        for (String position : new String[]{"укладання плитки 300х900", "укладання плитки дошка до 900 мм",
                "укладання плитки дошка до 1200 мм", "укладання плитки на шар клею більше 1 см"}) {
            assertThat(materialOf(position, "TILE_ADHESIVE%"))
                    .as("%s lays on", position)
                    .isEqualTo("TILE_ADHESIVE_C2");
        }
    }

    /** Мозаїка sits on a 3-4 mm notch: Kreisel 1,95-2,6, CM 117 Pro 1,9, CM 11/117 at 4 mm 2,5-2,6.
     *  3,9 was a wide-notch figure applied to the smallest tile there is. */
    @Test
    void mosaicBuysTheAdhesiveAThinBedActuallyTakes() {
        for (String position : new String[]{"укладання мозаїки", "облицювання мозаїкою обсягів",
                "облицювання радіусних поверхонь мозаїкою", "ремонт облицювання з мозаїки"}) {
            assertThat(qty(position, "TILE_ADHESIVE_C2")).as("%s", position)
                    .isEqualByComparingTo("2.6");
        }
    }

    /** A ceramic parquet / small plank is a 12 mm notch by V137's own rule — 7,8, not 6,5. */
    @Test
    void aCeramicParquetTakesTheTwelveMillimetreNotch() {
        assertThat(qty("укладання керамічного паркету, дрібної дошки", "TILE_ADHESIVE_C1"))
                .isEqualByComparingTo("7.8");
    }

    /**
     * Grout is GEOMETRY: a bigger tile has fewer metres of joint per m², so the figure falls as the
     * format grows. 0,4 kg/m² is CE 33/40's 10-30 cm answer and it was on every position, including
     * a 1,6 × 3,2 m slab with almost no joint at all.
     */
    @Test
    void groutFallsAsTheFormatGrows() {
        assertThat(qty("укладання плитки 300х300", "TILE_GROUT"))
                .as("the geometry 0,4 was written for").isEqualByComparingTo("0.4");
        assertThat(qty("укладання плитки 600х600", "TILE_GROUT")).isEqualByComparingTo("0.15");
        assertThat(qty("укладання плитки 1200х2400 мм", "TILE_GROUT")).isEqualByComparingTo("0.1");
        assertThat(qty("укладання керамограніту 20 мм", "TILE_GROUT")).isEqualByComparingTo("0.1");
    }

    /**
     * …and clinker goes the other way. CE 43 gives 1,2 kg/m² for 10×10 at a 5 mm joint, and 0,8 for
     * a 30×30 floor tile at 10 mm. Both rows also record the joint they were written for
     * ({@code baseline_param}), so a master's own 5 mm habit rescales them from THEIR figure and not
     * from the product-wide 2,5 (review B-49).
     */
    @Test
    void clinkerTakesFarMoreGroutAndSaysWhichJointItAssumed() {
        assertThat(qty("облицювання будинків клінкером «під цеглу»", "TILE_GROUT"))
                .isEqualByComparingTo("1.2");
        assertThat(baseline("облицювання будинків клінкером «під цеглу»", "TILE_GROUT"))
                .isEqualByComparingTo("5");
        assertThat(qty("укладання клінкерної підлогової плитки", "TILE_GROUT"))
                .isEqualByComparingTo("0.8");
        assertThat(baseline("укладання клінкерної підлогової плитки", "TILE_GROUT"))
                .isEqualByComparingTo("10");
    }

    /** A thick bed is grouted too, and the position shipped with adhesive only. */
    @Test
    void theThickBedPositionGotItsGroutRow() {
        assertThat(qty("укладання плитки на шар клею більше 1 см", "TILE_GROUT"))
                .isEqualByComparingTo("0.4");
    }

    /**
     * A movement joint takes silicone and «акрилення примикань» takes acrylic — neither is the
     * acoustic sealant V127 shipped for a drywall partition's perimeter. The RATE does not move
     * (0,025 l/m either way); what moves is which tube the master is sent to buy, and a cartridge is
     * 0,3 l against the acoustic tube's 0,6.
     */
    @Test
    void aMovementJointBuysSiliconeAndAnAcrylicJoinBuysAcrylic() {
        assertThat(materialOf("заповнення швів герметиком", "SEALANT%"))
                .isEqualTo("SEALANT_SILICONE");
        assertThat(materialOf("акрилення примикань", "SEALANT%")).isEqualTo("SEALANT_ACRYLIC");
        assertThat(jdbc.queryForObject(
                "SELECT package_size FROM material WHERE code = 'SEALANT_SILICONE'", BigDecimal.class))
                .isEqualByComparingTo("0.3");
    }

    /** Capacoll gives 0,15-0,3 kg/m² for a dispersive glue on paper; 0,01 was Quelyd's figure for a
     *  fleece wallpaper, which is «apply to the wall» and a different product entirely. */
    @Test
    void paperWallpaperBuysDispersiveGlue() {
        assertThat(qty("поклейка шпалер 50см без підбору", "WALLPAPER_GLUE"))
                .isEqualByComparingTo("0.2");
        assertThat(qty("поклейка фотошпалер", "WALLPAPER_GLUE")).isEqualByComparingTo("0.2");
    }

    /**
     * Ceresit CT 19 and CT 16 sell in 7,5 kg buckets and V137's own rule is that the smaller package
     * wins: rounding 3 kg of contact primer up to a 15 kg bucket is a bucket the master does not
     * need. Deep primer went to 5 l for the same reason — since B-51 it is on the standalone priming
     * step, where 10 l is over half a job.
     */
    @Test
    void thePrimerPackagesAreTheOnesTheShopSells() {
        assertThat(packageSize("PRIMER_CONTACT")).isEqualByComparingTo("7.5");
        assertThat(packageSize("PRIMER_QUARTZ")).isEqualByComparingTo("7.5");
        assertThat(packageSize("PRIMER_DEEP")).isEqualByComparingTo("5");
    }

    /**
     * B-51's whole point, stated as the thing a master would notice: a bathroom priced as laying +
     * waterproofing + plaster + grouting used to buy primer four times over.
     */
    @Test
    void noWorkPositionBuysPrimerAnyMore() {
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE m.code LIKE 'PRIMER%' AND n.unit = 'M2'
                   AND EXISTS (SELECT 1 FROM material_norm o JOIN material om ON om.id = o.material_id
                                WHERE o.owner_id IS NULL AND o.name_key = n.name_key
                                  AND o.unit = n.unit AND om.code NOT LIKE 'PRIMER%')
                """, Integer.class);

        assertThat(rows).as("primer rows left on m² positions that consume something else").isZero();
    }

    /**
     * The bound on that rule, and the reason for it: the standalone «Грунтування» step is priced per
     * SQUARE METRE, so it can only stand in for work measured the same way. A hidden-mount door is
     * priced per leaf — filled flush and painted with the wall's own paint (V139) — and no m² of
     * priming the master also prices covers it, so its primer stays.
     */
    @Test
    void aDoorPricedPerLeafKeepsItsOwnPrimer() {
        assertThat(qty("фарбування дверей прих. монтажу (одна сторона)", "PRIMER_DEEP"))
                .isEqualByComparingTo("0.30");
    }

    /** §3 row 11, on PRODUCT grounds rather than pricing ones: nobody primes a foam or PU baguette,
     *  which is why V139 already left primer off an ordinary door. */
    @Test
    void aFoamOrPolyurethaneMouldingIsNotPrimed() {
        Integer rows = jdbc.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE m.code = 'PRIMER_DEEP' AND n.unit = 'LINEAR_METER'
                   AND (n.name_key LIKE 'фарбування молдинга%'
                        OR n.name_key LIKE 'фарбування стельових багет%')
                """, Integer.class);

        assertThat(rows).isZero();
    }

    /** A position may never be left consuming nothing at all by a correction. */
    @Test
    void everyPositionStillConsumesSomething() {
        Integer empty = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT name_key, unit FROM material_norm WHERE owner_id IS NULL
                     GROUP BY name_key, unit HAVING count(*) = 0
                ) d
                """, Integer.class);

        assertThat(empty).isZero();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }

    private BigDecimal baseline(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.baseline_param FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }

    /** Which material of a family a position consumes — the question «C1 or C2» is asked this way. */
    private String materialOf(String nameKey, String codePattern) {
        return jdbc.queryForObject("""
                SELECT m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code LIKE ?
                """, String.class, nameKey, codePattern);
    }

    private BigDecimal packageSize(String code) {
        return jdbc.queryForObject("SELECT package_size FROM material WHERE code = ?",
                BigDecimal.class, code);
    }
}
