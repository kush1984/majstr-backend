package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.MaterialParamsRequest;
import com.majstr.backend.dto.StoredMaterialParams;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.MaterialParamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V142 — the calculator's three answers kept on the estimate, against the real schema.
 *
 * <p>Two halves Mockito cannot see. The CONSTRAINTS are the whole design: a CHECK pairing the basis
 * with the presence of a position (so nothing files a thickness with no line, or the estimate's one
 * perimeter against one), two PARTIAL unique indexes rather than one over a {@code COALESCE}, and
 * {@code ON DELETE CASCADE} on both foreign keys, which is what makes a deleted line take its
 * answers with it without a line of Java knowing. And the DUPLICATE carry runs on ids that only
 * exist once the copies are persisted — {@code copyBySourceId} collects nulls before the flush, the
 * same ordering trap the percent re-pointing beside it already lives with.</p>
 */
class MaterialParamIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialParamService paramService;
    @Autowired EstimateService estimateService;

    private UUID ownerId;
    private UUID projectId;
    private UUID estimateId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, plan)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 'PRO')
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        estimateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, 'Кошторис', 'DRAFT')
                """, estimateId, projectId);
    }

    // --- what the master answers, and what he takes back ------------------------------------

    @Test
    void anAnswerSurvivesTheScreenAndComesBackWholeOnAnyDevice() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");
        UUID box = addWork("Монтаж короба V142", Unit.LINEAR_METER, "12");

        paramService.save(estimateId, new MaterialParamsRequest(new BigDecimal("14.5"),
                Map.of(box, new BigDecimal("0.8")), Map.of(walls, new BigDecimal("15"))));

        StoredMaterialParams stored = paramService.load(estimateId);
        assertThat(stored.perimeter()).isEqualByComparingTo("14.5");
        assertThat(stored.sections()).containsOnlyKeys(box);
        assertThat(stored.sections().get(box)).isEqualByComparingTo("0.8");
        assertThat(stored.thicknesses().get(walls)).isEqualByComparingTo("15");
    }

    /**
     * Every card owns its own «Порахувати», so a request answers ONE question. Sending all three
     * would store the thickness suggestions still sitting pre-filled and unconfirmed in their fields
     * (V137: pre-filled visibly, never applied silently) the moment he answered the perimeter.
     */
    @Test
    void aSecondAnswerLeavesTheOtherTwoQuestionsExactlyAsTheyWere() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");
        paramService.save(estimateId, new MaterialParamsRequest(
                new BigDecimal("14.5"), null, Map.of(walls, new BigDecimal("15"))));

        StoredMaterialParams after = paramService.save(estimateId,
                new MaterialParamsRequest(new BigDecimal("18"), null, null));

        assertThat(after.perimeter()).isEqualByComparingTo("18");
        assertThat(after.thicknesses().get(walls)).as("не питаємо вдруге").isEqualByComparingTo("15");
    }

    /** «Not answered» has exactly one spelling — no row. A cleared field may not store a 0. */
    @Test
    void aClearedFieldForgetsTheAnswerRatherThanStoringZero() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");
        paramService.save(estimateId, new MaterialParamsRequest(
                new BigDecimal("14.5"), null, Map.of(walls, new BigDecimal("15"))));

        StoredMaterialParams after = paramService.save(estimateId, new MaterialParamsRequest(
                BigDecimal.ZERO, null, Map.of(walls, BigDecimal.ZERO)));

        assertThat(after.perimeter()).isNull();
        assertThat(after.thicknesses()).isEmpty();
        assertThat(rows()).isZero();
    }

    /**
     * A position deleted between the tap and the request is ordinary — offline the request replays
     * hours later. The foreign key would turn it into a failed save of every OTHER answer in the
     * same batch, so the unknown key is dropped and the rest lands.
     */
    @Test
    void aKeyNamingAPositionThisEstimateDoesNotHaveIsIgnoredNotRefused() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");

        StoredMaterialParams after = paramService.save(estimateId, new MaterialParamsRequest(
                null, null, Map.of(walls, new BigDecimal("15"),
                        UUID.randomUUID(), new BigDecimal("10"))));

        assertThat(after.thicknesses()).containsOnlyKeys(walls);
    }

    // --- the constraints, which are the design ----------------------------------------------

    @Test
    void aPerimeterMayNotNameAPositionAndAThicknessMayNotBeWithoutOne() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");

        assertThatThrownBy(() -> insert(walls, "PERIMETER", "14.5"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, "THICKNESS", "15"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroAndAStrayExtraDigitAreBothRefused() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");

        assertThatThrownBy(() -> insert(walls, "THICKNESS", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(walls, "THICKNESS", "1500"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** One answer per (position, question) — and per (estimate, question) for the one no line owns. */
    @Test
    void aQuestionIsAnsweredOnceAndTheTwoIndexesSayThatInTheirOwnSentences() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");
        UUID ceiling = addWork("Штукатурка стелі V142", Unit.M2, "10");
        insert(walls, "THICKNESS", "15");
        insert(null, "PERIMETER", "14.5");

        assertThatThrownBy(() -> insert(walls, "THICKNESS", "10"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, "PERIMETER", "18"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Two positions asked the same question is not a duplicate: one estimate plasters walls at
        // 15 mm and a ceiling at 10 (V137).
        insert(ceiling, "THICKNESS", "10");
        insert(walls, "SECTION", "0.4");
        assertThat(rows()).isEqualTo(4);
    }

    @Test
    void aDeletedPositionTakesItsAnswerWithItAndADeletedEstimateTakesThemAll() {
        UUID walls = addWork("Штукатурка стін V142", Unit.M2, "20");
        insert(walls, "THICKNESS", "15");
        insert(null, "PERIMETER", "14.5");

        jdbc.update("DELETE FROM estimate_items WHERE id = ?", walls);
        assertThat(rows()).as("answer about a line that is gone").isEqualTo(1);

        jdbc.update("DELETE FROM estimates WHERE id = ?", estimateId);
        assertThat(rows()).isZero();
    }

    // --- the duplicate ----------------------------------------------------------------------

    /**
     * The same walls at another price, so the answers come along — re-asking a короб's розгортка on
     * a copy the master made with one tap would be this table's own bug one level up. They have to
     * land on the COPY's line ids, which do not exist until the copies are flushed.
     */
    @Test
    void aDuplicateCarriesTheAnswersOverOntoItsOwnLines() {
        UUID box = addWork("Монтаж короба V142", Unit.LINEAR_METER, "12");
        paramService.save(estimateId, new MaterialParamsRequest(
                new BigDecimal("14.5"), Map.of(box, new BigDecimal("0.8")), null));

        EstimateResponse copy = estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, new BigDecimal("15"), false, null), ownerId);

        UUID copiedBox = jdbc.queryForObject(
                "SELECT id FROM estimate_items WHERE estimate_id = ?", UUID.class, copy.id());
        StoredMaterialParams carried = paramService.load(copy.id());
        assertThat(carried.perimeter()).isEqualByComparingTo("14.5");
        assertThat(carried.sections()).containsOnlyKeys(copiedBox);
        assertThat(carried.sections().get(copiedBox)).isEqualByComparingTo("0.8");
        // The source keeps its own: they are two estimates of the same room, not one moved.
        assertThat(paramService.load(estimateId).sections()).containsOnlyKeys(box);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Names carry a V142 suffix: the container's schema is SHARED, and the community-price
     *  aggregation counts every master who ever priced a position by its literal name. */
    private UUID addWork(String name, Unit unit, String quantity) {
        return estimateService.addItem(estimateId, new EstimateItemRequest(
                        ItemType.WORK, name, null, unit, new BigDecimal(quantity),
                        new BigDecimal("100"), null, null, false, null, null), ownerId)
                .id();
    }

    private void insert(UUID itemId, String basis, String value) {
        jdbc.update("""
                INSERT INTO estimate_material_param (id, estimate_id, estimate_item_id, basis, value)
                VALUES (?, ?, ?, ?, ?::numeric)
                """, UUID.randomUUID(), estimateId, itemId, basis, value);
    }

    private int rows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM estimate_material_param WHERE estimate_id = ?",
                Integer.class, estimateId);
    }
}
