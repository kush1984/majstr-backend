package com.majstr.backend.integration;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.MaterialAliasRequest;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.NormPosition;
import com.majstr.backend.dto.PerPositionAnswers;
import com.majstr.backend.dto.PositionMatch;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.MaterialNormValidationException;
import com.majstr.backend.service.MaterialAliasService;
import com.majstr.backend.service.MaterialCalculatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V204 — «рахувати як»: a line in the master's own words, which no norm knows, is named on the materials
 * screen with the closest library positions; his pick is remembered for that wording in every estimate.
 */
class MaterialAliasIntegrationTest extends IntegrationTestBase {

    private static final String OWN = "Фарбування стін за 2 рази";

    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialCalculatorService calculatorService;
    @Autowired MaterialAliasService aliasService;

    private UUID ownerId;
    private UUID projectId;
    private UUID estimateId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')",
                projectId, ownerId);
        estimateId = newEstimate();
    }

    @Test
    void theSameJobInOtherWordsIsAskedAboutOnceWithTheOneClosePosition() {
        UUID line = addWork(estimateId, OWN, "M2", "40", "PAINTER");
        PositionMatch match = matchOf(calc(estimateId), line);

        assertThat(match.kind()).isEqualTo(PositionMatch.Kind.UNMATCHED);
        // One question — a painting position, never the plaster that only shares «стін» (owner, 2026-11-13).
        assertThat(match.suggestions()).singleElement().satisfies(p -> {
            assertThat(p.unit()).isEqualTo(Unit.M2);
            assertThat(p.name().toLowerCase()).contains("фарбування");
        });
    }

    @Test
    void hisOwnWorkIsNotMatchedToAnythingThatOnlySharesAWord() {
        UUID line = addWork(estimateId, "Реставрація гіпсової ліпнини на фасаді", "M2", "12", "PAINTER");
        PositionMatch match = matchOf(calc(estimateId), line);
        assertThat(match.kind()).isEqualTo(PositionMatch.Kind.UNMATCHED);
        assertThat(match.suggestions()).isEmpty();
    }

    @Test
    void hisPickIsCountedAndRememberedForTheWordingInAnotherEstimate() {
        NormPosition paint = painting();
        aliasService.save(ownerId, new MaterialAliasRequest(OWN, Unit.M2, paint.key()));

        UUID line = addWork(estimateId, OWN, "M2", "40", "PAINTER");
        MaterialCalculationResponse result = calc(estimateId);
        assertThat(matchOf(result, line)).satisfies(m -> {
            assertThat(m.kind()).isEqualTo(PositionMatch.Kind.MASTER);
            assertThat(m.asKey()).isEqualTo(paint.key());
            assertThat(m.asName()).isEqualTo(paint.name());
        });
        assertThat(result.materials()).flatExtracting(CalculatedMaterialLine::sources)
                .anySatisfy(s -> assertThat(s.estimateItemId()).isEqualTo(line));

        // Another estimate, the same words written a little differently in spacing and case.
        UUID other = newEstimate();
        UUID again = addWork(other, "  фарбування   стін за 2 рази ", "M2", "10", null);
        assertThat(matchOf(calc(other), again).kind()).isEqualTo(PositionMatch.Kind.MASTER);
    }

    @Test
    void noMaterialsIsAnAnswerAndForgettingAsksAgain() {
        UUID line = addWork(estimateId, "Монтаж насосної групи", "PIECE", "1", "PLUMBING");
        aliasService.save(ownerId, new MaterialAliasRequest("Монтаж насосної групи", Unit.PIECE, null));
        assertThat(matchOf(calc(estimateId), line).kind()).isEqualTo(PositionMatch.Kind.NO_MATERIALS);

        aliasService.delete(ownerId, "Монтаж насосної групи", Unit.PIECE);
        aliasService.delete(ownerId, "Монтаж насосної групи", Unit.PIECE); // idempotent
        assertThat(matchOf(calc(estimateId), line).kind()).isEqualTo(PositionMatch.Kind.UNMATCHED);
    }

    @Test
    void aLineReadByItsOwnNameOrTheTileRuleIsReportedHonestly() {
        UUID library = addWork(estimateId, "Укладання плитки 300х600", "M2", "10", "TILING");
        UUID imported = addWork(estimateId, "Укладання плитки на підлогу 60×60", "M2", "10", "TILING");
        MaterialCalculationResponse result = calc(estimateId);
        assertThat(result.matches()).noneMatch(m -> m.estimateItemId().equals(library));
        assertThat(matchOf(result, imported)).satisfies(m -> {
            assertThat(m.kind()).isEqualTo(PositionMatch.Kind.RULE);
            assertThat(m.asKey()).isEqualTo("укладання плитки 600х600");
        });
    }

    @Test
    void theSameWordsSpelledDifferentlyAreReadAsTheLibraryPosition() {
        UUID ge = addWork(estimateId, "Ґрунтування кварцґрунтом", "M2", "10", "PAINTER");
        UUID latinX = addWork(estimateId, "Укладання плитки 300x600", "M2", "10", "TILING");
        UUID comma = addWork(estimateId, "Грунтовка поверхонь перед шпаклівкою, фарбуванням", "M2", "10", null);
        MaterialCalculationResponse result = calc(estimateId);

        assertThat(matchOf(result, ge)).satisfies(m -> {
            assertThat(m.kind()).isEqualTo(PositionMatch.Kind.RULE);
            assertThat(m.asKey()).isEqualTo("грунтування кварцгрунтом");
        });
        assertThat(matchOf(result, latinX).asKey()).isEqualTo("укладання плитки 300х600");
        assertThat(matchOf(result, comma).asKey()).isEqualTo("грунтовка поверхонь перед шпаклівкою фарбуванням");
        assertThat(result.materials()).flatExtracting(CalculatedMaterialLine::sources)
                .extracting(s -> s.estimateItemId())
                .contains(ge, latinX, comma);
    }

    @Test
    void spellingAloneNeverPicksBetweenTwoPositions() {
        // Two library positions differ only in words, never in spelling — so a shorter wording is not theirs.
        assertThat(MaterialAliasService.spelling("Ґрунтування «кварц-ґрунтом»!"))
                .isEqualTo("грунтування кварц грунтом");
        assertThat(MaterialAliasService.spellingIndex(List.of(
                new NormPosition("a b", "A b", Unit.M2, null),
                new NormPosition("a, b", "A, b", Unit.M2, null),
                new NormPosition("c", "C", Unit.M2, null)))).containsOnlyKeys("c|M2");
    }

    @Test
    void onlyALibraryPositionOfTheSameUnitCanBeChosen() {
        assertThatThrownBy(() -> aliasService.save(ownerId, new MaterialAliasRequest(OWN, Unit.M2, "немає такої позиції")))
                .isInstanceOf(MaterialNormValidationException.class);
        NormPosition paint = painting();
        assertThatThrownBy(() -> aliasService.save(ownerId, new MaterialAliasRequest(OWN, Unit.PIECE, paint.key())))
                .isInstanceOf(MaterialNormValidationException.class);
    }

    @Test
    void anotherMastersAnswerIsNotMine() {
        UUID stranger = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Інший', '+380', 'ФОП', ?, TRUE)
                """, stranger, stranger + "@t.ua", stranger + "@t.ua", stranger.toString().substring(0, 8));
        aliasService.save(stranger, new MaterialAliasRequest(OWN, Unit.M2, painting().key()));
        UUID line = addWork(estimateId, OWN, "M2", "40", "PAINTER");
        assertThat(matchOf(calc(estimateId), line).kind()).isEqualTo(PositionMatch.Kind.UNMATCHED);
    }

    @Test
    void theMaterialsDoorIsOfferedForAnEstimateOnlyItsOwnWordsDescribe() {
        addWork(estimateId, OWN, "M2", "40", "PAINTER");
        assertThat(calculatorService.availability(estimateId, ownerId).available()).isTrue();
    }

    // --- helpers ----------------------------------------------------------------------------

    private NormPosition painting() {
        return aliasService.libraryPositions().stream()
                .filter(p -> p.unit() == Unit.M2 && p.key().equals("фарбування стін водоемульсійною фарбою"))
                .findFirst().orElseThrow();
    }

    private MaterialCalculationResponse calc(UUID estimate) {
        return calculatorService.calculate(estimate, ownerId, BigDecimal.TEN, null, PerPositionAnswers.NONE);
    }

    private static PositionMatch matchOf(MaterialCalculationResponse r, UUID line) {
        return r.matches().stream().filter(m -> m.estimateItemId().equals(line)).findFirst()
                .orElseThrow(() -> new AssertionError("no match reported for " + line + " in " + r.matches()));
    }

    private UUID newEstimate() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', 'DRAFT')", id, projectId);
        return id;
    }

    private UUID addWork(UUID estimate, String name, String unit, String quantity, String trade) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade)
                VALUES (?, ?, 'WORK', ?, ?, ?::numeric, 100, 100, 0, ?)
                """, id, estimate, name, unit, quantity, trade);
        return id;
    }
}
