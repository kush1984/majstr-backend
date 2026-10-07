package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V146 — the trade-by-trade review's batch 1, read back off the applied schema.
 *
 * <p>Everything V146 touches is a figure or a SEQUENCE that buys the wrong quantity today, and a
 * data-only migration's own {@code DO $$} checks only prove it landed once. What they cannot do is
 * stop a later migration from quietly putting the old number back — which is what every assertion
 * here is for.</p>
 *
 * <p>Two of them also stand in for a deliberate {@code RAISE WARNING}: a bundle line resolves its
 * price off the master's own catalog by NAME (V112), so a name this migration wrote that no library
 * position matches would apply at 0 ₴ and say nothing. The migration only warns, because a catalog
 * position is something an admin can rename between two deploys (review B-50); CI is where it is a
 * hard failure.</p>
 */
class TradeReviewBatchOneOnLiveDataIntegrationTest extends IntegrationTestBase {

    /** V148 renamed two of the five; the bundle (and its id) is the same one V146 primed. */
    private static final List<String> PRIMED_BUNDLES = List.of(
            "Басейн та мозаїка", "Душова врівень з підлогою (трап)",
            "Облицювання натуральним каменем", "Сходи плиткою", "Тераса, балкон, вулиця");

    /** The ten positions two trades ship, which a norm filed under one of them answered for once. */
    private static final List<String> SHARED_POSITIONS = List.of(
            "демонтаж гіпсокартонної стелі", "демонтаж перегородки з гіпсокартону",
            "установка люка-ревізії простого", "армування фасаду сітка перетяжка",
            "грунтовка поверхні кварцгрунтом", "декоративна штукатурка фасаду короїд баранець",
            "демонтаж будівельного риштування", "монтаж будівельного риштування",
            "фарбування фасаду", "гідроізоляція сухою сумішшю");

    @Autowired JdbcTemplate jdbc;

    // ---- §1 UD on a wall is not a perimeter -------------------------------------------------

    /**
     * On a ceiling the UD really does run the room's outline once, which is why the norm was written
     * as a PERIMETER at all. On a WALL it is the top and bottom track of the frame, so it scales
     * with the area being clad — and because a perimeter is asked ONCE for the whole estimate (the
     * larger per-metre figure winning), a flat with three lined walls bought one wall's track.
     */
    @Test
    void aWallsTrackScalesWithItsAreaAndACeilingsStillWithThePerimeter() {
        for (String wall : List.of("монтаж гіпсокартону на стіни",
                "каркасна звукоізоляція (гкл в два слоя) стін")) {
            Map<String, Object> norm = norm(wall, "PROFILE_UD");
            assertThat(norm.get("basis")).as("%s", wall).isEqualTo("QUANTITY");
            assertThat((BigDecimal) norm.get("qty_per_unit")).as("%s", wall)
                    .isEqualByComparingTo("0.7");
            assertThat(norm.get("default_param")).as("a quantity basis asks nothing").isNull();
        }

        for (String ceiling : List.of("монтаж гіпсокартону на стелю рівну",
                "каркасна звукоізоляція (гкл в два слоя) стелі")) {
            Map<String, Object> norm = norm(ceiling, "PROFILE_UD");
            assertThat(norm.get("basis")).as("%s", ceiling).isEqualTo("PERIMETER");
            assertThat((BigDecimal) norm.get("qty_per_unit")).as("%s", ceiling)
                    .isEqualByComparingTo("1.05");
        }
    }

    // ---- §2 mineral wool, on the wall and only there -----------------------------------------

    /**
     * «Звукоізоляція та утеплення» prices the standalone «Звукоізоляція стін мінеральною ватою» and
     * then the two-layer frame position, which carried its own 1,05 — so the wall's wool was bought
     * twice. The frame position buys the frame and the sheets; the insulation position buys the
     * insulation.
     *
     * <p>V146 had to leave the CEILING twin its wool: the library had no «Звукоізоляція стелі
     * мінеральною ватою», so taking it off would have bought none at all. V148 added that position,
     * gave it the wall wool's norm and took the wool off the ceiling frame in the same statement —
     * so both frames now buy the frame, and each wool position buys the wool.</p>
     */
    @Test
    void bothFramePositionsStoppedBuyingWoolOnceEachHadAWoolPosition() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стін'
                """)).as("the wall frame position's own wool row").isZero();

        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стелі'
                """)).as("the ceiling frame position's own wool row (V148)").isZero();

        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'звукоізоляція стелі мінеральною ватою'
                """)).as("the ceiling's standalone position buys it (V148)").isEqualTo(1);

        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'звукоізоляція стін мінеральною ватою'
                """)).as("the standalone position still buys it").isEqualTo(1);
    }

    // ---- §3 grout: the hybrid ruling ---------------------------------------------------------

    /**
     * Grout is bought ONCE, and unlike primer the position that buys it is the LAYING one — only it
     * knows the format, and the format is the whole geometry. So a grouting step that FOLLOWS laying
     * records a verdict rather than losing its row: V127's shape, {@code material_id IS NULL AND
     * qty_per_unit IS NULL}, which is what lets the calculator say «ця позиція нічого не споживає»
     * instead of staying silent about a position it has no answer for.
     */
    @Test
    void aGroutingStepThatFollowsLayingConsumesNothing() {
        for (String step : List.of("заповнення швів цементною сумішшю",
                "заповнення швів цементною сумішшю з латексом",
                "затирання швів цементною сумішшю з латексом")) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT material_id, qty_per_unit FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND unit = 'M2'
                    """, step);

            assertThat(rows).as("%s keeps a row to say it with", step).isNotEmpty();
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("material_id")).as("%s names no material", step).isNull();
                assertThat(row.get("qty_per_unit")).as("%s names no coefficient", step).isNull();
            });
        }
    }

    /**
     * …and a standalone grouting JOB keeps its own norm, because no laying line stands beside it to
     * have bought the grout already. «Заміна затірки швів» is the raked-out joint of a finished
     * floor; «Заповнення товстого шва напівсухою сумішшю» is a 10 mm joint filled by hand.
     */
    @Test
    void aStandaloneGroutingJobKeepsItsOwnNorm() {
        assertThat(qty("заміна затірки швів", "TILE_GROUT")).isEqualByComparingTo("0.4");
        assertThat(qty("затирання швів у декоративній плитці, мозаїці", "TILE_GROUT"))
                .isEqualByComparingTo("0.6");
        assertThat(qty("затирання швів від 3 мм цементною сумішшю", "TILE_GROUT"))
                .as("«від 3 мм» was carrying a 6 mm figure under a 3 mm name")
                .isEqualByComparingTo("0.4");
        assertThat(qty("заповнення товстого шва напівсухою сумішшю", "TILE_GROUT"))
                .isEqualByComparingTo("0.8");
    }

    /**
     * A joint this wide comes from the WORK, not from the master's hand, so {@code baseline_param}
     * is what stops his {@code TILE_JOINT_MM} habit from rescaling it: the service exempts a norm
     * whose baseline is 5 mm or more ({@code WIDE_JOINT_MM}). A brick slip course has a 10 mm
     * masonry joint and a thick joint filled with a semi-dry mix the same, and neither was ever his
     * to describe.
     */
    @Test
    void aWideJointRecordsTheWidthItWasWrittenFor() {
        assertThat(baseline("облицювання будинків клінкером «під цеглу»", "TILE_GROUT"))
                .isEqualByComparingTo("10");
        assertThat(baseline("укладання клінкерної підлогової плитки", "TILE_GROUT"))
                .isEqualByComparingTo("10");
        assertThat(baseline("заповнення товстого шва напівсухою сумішшю", "TILE_GROUT"))
                .as("a 10 mm joint filled by hand").isEqualByComparingTo("10");
        assertThat(baseline("укладання керамограніту 20 мм", "TILE_GROUT"))
                .as("3 mm outdoors is still the master's business to rescale")
                .isEqualByComparingTo("3");
    }

    /** A step is ~0,45 m² of tile per running metre, so its per-m² figure is the 300×300-class one —
     *  the SECTION basis is what multiplies it out, and 0,4 was the old flat «anything small». */
    @Test
    void aStairsGroutFollowsTheSameGeometryAsTheFloorFormat() {
        for (String stairs : List.of("укладання плитки на сходи та підсходинок",
                "облицювання сходових маршів", "облицювання радіусних сходів (без підступка)")) {
            assertThat(qty(stairs, "TILE_GROUT")).as("%s", stairs).isEqualByComparingTo("0.25");
        }
    }

    // ---- §4 tile adhesive --------------------------------------------------------------------

    /**
     * Clinker floor tile is a pressed, strongly absorbent body on an 8-10 mm notch, which is C2
     * territory and not C1's 5,2. A thin brick slip on a facade is BUTTERED rather than combed, so
     * it needs less than a large-format floor's 8,5; a pool is C2 at an 8 mm notch.
     */
    @Test
    void theAdhesiveClassAndRateMatchTheNotchTheWorkTakes() {
        assertThat(materialOf("укладання клінкерної підлогової плитки", "TILE_ADHESIVE%"))
                .isEqualTo("TILE_ADHESIVE_C2");
        assertThat(qty("укладання клінкерної підлогової плитки", "TILE_ADHESIVE_C2"))
                .isEqualByComparingTo("7.0");
        assertThat(qty("облицювання будинків клінкером «під цеглу»", "TILE_ADHESIVE_C2"))
                .isEqualByComparingTo("6.0");
        assertThat(qty("облицювання басейнів", "TILE_ADHESIVE_C2")).isEqualByComparingTo("7.0");
    }

    /**
     * «Дикий камінь» has no format and no fixed thickness, so a flat kg/m² is a guess about the
     * stone the master happens to have bought. It is the case THICKNESS exists for: he states the
     * bed, the calculator multiplies. 1,35 kg per mm is the figure V137 already uses for the two
     * other thickness-driven adhesive rows, and the 10 mm default is pre-filled VISIBLY.
     */
    @Test
    void wildStoneAsksForTheBedInsteadOfGuessingIt() {
        for (String stone : List.of("укладання \"дикого каменю\" піщаник, сланець",
                "облицювання «диким каменем» фасаду будинку, парканів")) {
            Map<String, Object> norm = norm(stone, "TILE_ADHESIVE_C2");
            assertThat(norm.get("basis")).as("%s", stone).isEqualTo("THICKNESS");
            assertThat((BigDecimal) norm.get("qty_per_unit")).as("%s", stone)
                    .isEqualByComparingTo("1.35");
            assertThat((BigDecimal) norm.get("default_param")).as("%s", stone)
                    .isEqualByComparingTo("10");
        }
    }

    // ---- §5 wallpaper glue and the package sizes ----------------------------------------------

    /**
     * A package size is the LAST multiplication the shopping list does, so one that is wrong rounds
     * the whole answer onto the wrong number of packs. A filler primer is a 5 l canister, a silicone
     * cartridge holds 280 ml, and glass fibre comes on a 1 × 20 m roll — which had no package at
     * all, so a 40 m² job asked the master to buy «40 m²» of it.
     */
    @Test
    void aPackageIsWhatTheShopActuallyHandsHim() {
        assertThat(packageSize("PRIMER_FILLER")).isEqualByComparingTo("5");
        assertThat(packageSize("SEALANT_SILICONE")).isEqualByComparingTo("0.28");

        Map<String, Object> roll = jdbc.queryForMap("""
                SELECT package_size, package_unit, package_name FROM material WHERE code = 'FIBERGLASS'
                """);
        assertThat((BigDecimal) roll.get("package_size")).isEqualByComparingTo("20");
        assertThat(roll.get("package_unit")).isEqualTo("M2");
        assertThat(roll.get("package_name")).isEqualTo("рулон");
    }

    // ---- §6 primer: the right product, and «once» one unit over --------------------------------

    /**
     * «Грунтовка поверхонь перед штукатуркою/армуванням» IS a priming position, so V145 rightly let
     * it keep a row — but the row named бетоноконтакт, which is for a smooth non-absorbent slab and
     * has a position of its own. A wall about to be plastered or meshed takes a deep primer, the
     * same reason V145 took бетоноконтакт off the gypsum-plaster rows.
     */
    @Test
    void thePrimingPositionPrimesWithAPrimerAndNotWithConcreteContact() {
        assertThat(materialOf("грунтовка поверхонь перед штукатуркою армуванням", "PRIMER%"))
                .isEqualTo("PRIMER_DEEP");
        assertThat(qty("грунтовка поверхонь перед штукатуркою армуванням", "PRIMER_DEEP"))
                .isEqualByComparingTo("0.15");
    }

    /**
     * And V145's «primer is bought once» reaches the running metre too: three LINEAR_METER positions
     * primed a reveal or a box INSIDE the painting/puttying work, while «Грунтування укосів» is the
     * standalone running-metre priming step the master prices beside them. That is the m² ruling
     * exactly, one unit over.
     */
    @Test
    void aRevealIsNotPrimedTwiceEitherAndTheStandaloneStepStillIs() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE m.code = 'PRIMER_DEEP' AND n.unit = 'LINEAR_METER'
                   AND n.name_key IN ('фарбування укосів',
                                      'шпаклівка коробів укосів ніш під фарбування',
                                      'шпаклівка коробів, укосів, ніш та виступів під фарбування')
                """)).as("rows left on a position that also consumes paint or putty").isZero();

        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'PRIMER_DEEP' AND n.unit = 'LINEAR_METER'
                   AND n.name_key = 'грунтування укосів'
                """)).as("the standalone step is the one that buys it").isEqualTo(1);
    }

    // ---- §7 a norm filed under one trade for a position two trades ship -------------------------

    /**
     * {@code catalog_items} holds ONE row per (owner, name, type, unit), so a position two trades
     * ship is stored once (V118) — but the NORM carries a trade, and the trade is a FILTER on the
     * answer. Ten shipped positions were filed under whichever trade happened to write the norm, so
     * the other trade asked for materials and got nothing: a builder's facade bought no primer, no
     * mesh, no plaster and no paint; a plumber's inspection hatch bought no hatch.
     *
     * <p>The answer is V137's — re-file to {@code trade = NULL}, never duplicate per trade, which
     * would be two coefficients for one job.</p>
     */
    @Test
    void aPositionTwoTradesShipIsAnsweredForEitherOfThem() {
        List<Map<String, Object>> filed = jdbc.queryForList("""
                SELECT name_key, trade FROM material_norm
                 WHERE owner_id IS NULL AND trade IS NOT NULL AND name_key IN (?,?,?,?,?,?,?,?,?,?)
                """, SHARED_POSITIONS.toArray());

        assertThat(filed).as("shared shipped norms still filed under one trade").isEmpty();

        // And re-filed, not duplicated: one row per (position, material), not one per trade.
        assertThat(count("""
                SELECT count(*) FROM (
                    SELECT name_key, unit, material_id FROM material_norm
                     WHERE owner_id IS NULL AND name_key IN (?,?,?,?,?,?,?,?,?,?)
                     GROUP BY name_key, unit, material_id HAVING count(*) > 1
                ) d
                """, SHARED_POSITIONS.toArray())).as("duplicated per trade").isZero();
    }

    // ---- §8 the five tiling bundles that never primed anything ---------------------------------

    /**
     * A default bundle is a SEQUENCE, and the sequence is what the master prices. «Ґрунтівка
     * поверхні» is the position that buys the primer for every tiling job after V145, so a bundle
     * without it prices no priming and buys no primer — while all five below start on a screed or a
     * fresh plaster coat that has to be primed before anything is stuck to it.
     *
     * <p>Each step goes where the work actually happens — after the demolition/screed, before the
     * waterproofing or the first adhesive — because the order IS the content, so appending it at the
     * end would be a different bug.</p>
     */
    @Test
    void theFiveTilingBundlesPrimeBeforeTheyStick() {
        for (String bundle : PRIMED_BUNDLES) {
            assertThat(count("""
                    SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'TILING' AND t.name = ?
                       AND lower(trim(i.name)) = 'ґрунтівка поверхні'
                    """, bundle)).as("%s primes exactly once", bundle).isEqualTo(1);
        }

        // V148 opened every bundle with protection and the demolition it needs, so the exact
        // positions moved; what is pinned is the place relative to the base it primes.
        assertThat(sortOrder("Басейн та мозаїка", "ґрунтівка поверхні"))
                .as("after the screed, before the pool's waterproofing")
                .isGreaterThan(sortOrder("Басейн та мозаїка", "штукатурка, стяжка басейну"));
        assertThat(sortOrder("Тераса, балкон, вулиця", "ґрунтівка поверхні"))
                .as("after the demolition and the screed")
                .isGreaterThan(sortOrder("Тераса, балкон, вулиця",
                        "влаштування стяжки з ухилом (балкон, тераса)"));
    }

    /** «Підлога великоформатом» already had the step, one line too late: a self-levelling floor is
     *  poured ONTO a primed base, so priming belongs before it and not after. */
    @Test
    void aSelfLevellingFloorIsPouredOntoAPrimedBase() {
        assertThat(sortOrder("Підлога великоформатом, керамограніт", "ґрунтівка поверхні"))
                .isLessThan(sortOrder("Підлога великоформатом, керамограніт",
                        "влаштування наливної підлоги"));
    }

    // ---- §9 a «комплекс» position inside a bundle of its own parts -----------------------------

    /**
     * «Утеплення фасаду» priced the facade rollup AND the four positions that make it up, so the
     * whole facade was charged twice; «Покрівля двоскатна» did the same with the roof rollup. A
     * rollup belongs in a bundle of its own, never beside its own parts. «Кладка цегла» mixed mortar
     * WITH gravel, which is concrete and not masonry mortar — the library has the right position and
     * the bundle simply named the wrong one. (V148 renamed that bundle «Коробка будинку з цегли».)
     */
    @Test
    void aBundleNoLongerPricesARollupBesideItsOwnParts() {
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
                   AND lower(trim(i.name)) IN ('утеплення фасада комплекс пінопласт сітка декор штукатурка',
                                               'монтаж двоскатного даху комплекс')
                """)).as("rollup lines still sitting beside their own parts").isZero();

        assertThat(count("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER' AND t.name = 'Коробка будинку з цегли'
                   AND lower(trim(i.name)) = 'приготування розчину для кладки без щебня'
                """)).as("masonry mixes mortar, not concrete").isEqualTo(1);
    }

    /** `sort_order` IS the content, so a deletion may not leave a hole behind it — the next reorder
     *  would renumber from a sequence the master never saw. */
    @Test
    void aDeletionLeavesNoHoleInTheSequence() {
        for (String bundle : List.of("Утеплення фасаду", "Покрівля двоскатна")) {
            List<Integer> orders = jdbc.queryForList("""
                    SELECT i.sort_order FROM estimate_template_items i
                      JOIN estimate_templates t ON t.id = i.template_id
                     WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER' AND t.name = ?
                     ORDER BY i.sort_order
                    """, Integer.class, bundle);

            assertThat(orders).as("%s", bundle).isNotEmpty();
            assertThat(orders).as("%s is 0..n-1 with no gap and no repeat", bundle)
                    .containsExactlyElementsOf(IntStream.range(0, orders.size()).boxed().toList());
        }
    }

    // ---- §10 a system is started after it has held pressure -------------------------------------

    /**
     * Six bundles ran «Запуск системи …» and then «Перевірка системи … тиском», which is the test
     * done on a system already filled and running — not a pressure test at all. The point of
     * опресування is to find the leak while the pipe is still exposed and nothing downstream is at
     * risk.
     */
    @Test
    void aSystemIsTestedBeforeItIsStarted() {
        assertThat(count("""
                SELECT count(*) FROM estimate_templates t
                  JOIN estimate_template_items z ON z.template_id = t.id
                  JOIN estimate_template_items p ON p.template_id = t.id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'PLUMBING'
                   AND z.name LIKE 'Запуск системи %'
                   AND p.name LIKE 'Перевірка системи % тиском'
                   AND split_part(z.name, ' ', 3) = split_part(p.name, ' ', 3)
                   AND p.sort_order > z.sort_order
                """)).as("bundles that still start a system before testing it").isZero();
    }

    /**
     * In «САНТЕХНІКА» the chase was closed BETWEEN them, which is worse than the wrong order: the
     * water pipe was buried before it had been tested. The sequence ends as lay → test → close the
     * chase → start. V148 retired that dump; the apartment's rough-in bundle carries the rule now.
     */
    @Test
    void theChaseIsClosedAfterTheTestAndBeforeTheStart() {
        String bundle = "Квартира — вузол вводу, розводка води й каналізації";
        int test = sortOrder("PLUMBING", bundle, "перевірка системи водопроводу тиском");
        int chase = sortOrder("PLUMBING", bundle, "заробка штроб (сантехніка)");
        int start = sortOrder("PLUMBING", bundle, "запуск системи водопроводу");

        assertThat(test).as("tested while the pipe is still exposed").isLessThan(chase);
        assertThat(chase).as("and started once it is closed up").isLessThan(start);
    }

    // ---- §11 the names this migration wrote must resolve to a price -----------------------------

    /**
     * The migration's own check for this is a {@code RAISE WARNING} on purpose: a catalog position is
     * something an admin can rename between two deploys, so a shipped migration may not abort a
     * deploy over it (review B-50). Here it is a hard failure, which is the whole point of the pair
     * — a bundle line whose name matches no library position applies at 0 ₴ and says nothing.
     */
    @Test
    void everyBundleLineV146WroteResolvesToACatalogPosition() {
        for (String name : List.of("Ґрунтівка поверхні", "Приготування розчину для кладки без щебня")) {
            assertThat(count("""
                    SELECT count(*) FROM catalog_templates WHERE lower(trim(name)) = lower(trim(?))
                    """, name)).as("«%s» must name a library position to be priced", name)
                    .isPositive();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, Object> norm(String nameKey, String code) {
        return jdbc.queryForMap("""
                SELECT n.basis, n.qty_per_unit, n.default_param, n.baseline_param, n.trade
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, nameKey, code);
    }

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

    private int sortOrder(String bundle, String itemNameKey) {
        return sortOrder("TILING", bundle, itemNameKey);
    }

    private int sortOrder(String trade, String bundle, String itemNameKey) {
        Integer order = jdbc.queryForObject("""
                SELECT i.sort_order FROM estimate_template_items i
                  JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = ? AND t.name = ?
                   AND lower(trim(i.name)) = ?
                """, Integer.class, trade, bundle, itemNameKey);
        return order == null ? -1 : order;
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? -1 : count;
    }
}
