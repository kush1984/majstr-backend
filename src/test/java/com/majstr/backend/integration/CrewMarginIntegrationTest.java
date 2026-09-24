package com.majstr.backend.integration;

import com.majstr.backend.dto.CrewMarginResponse;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.dto.SignedEstimatePanelResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ObjectExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Бригаді / Твоя націнка» — the бригадир's own half of the money, end to end.
 *
 * <p>Mockito cannot see the thing most worth guarding here. The crew view is produced by running
 * the SAME {@code EstimateMath} over rebuilt lines, and that method writes {@code lineTotal} into
 * every entity it is handed — so if the rebuild ever handed it the MANAGED rows, JPA would flush the
 * crew's prices into the sheet the client signed. Only a real persistence context can prove it does
 * not.</p>
 */
class CrewMarginIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired EstimateService estimateService;
    @Autowired ObjectExpenseService economyService;

    private UUID ownerId;
    private UUID projectId;
    private UUID parentId;
    private int nextActNumber = 1;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, plan)
                VALUES (?, ?, ?, 'x', 'Бригадир', '+380', 'ФОП', ?, 'PRO')
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        parentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, 'Ціни бригади', 'DRAFT')
                """, parentId, projectId);
    }

    // --- helpers ---------------------------------------------------------------------------

    private void addWork(String name, String quantity, String price) {
        estimateService.addItem(parentId, new EstimateItemRequest(
                ItemType.WORK, name, null, Unit.M2, new BigDecimal(quantity), new BigDecimal(price),
                null, null, false, null, null), ownerId);
    }

    private void addMaterial(String name, String quantity, String price) {
        estimateService.addItem(parentId, new EstimateItemRequest(
                ItemType.MATERIAL, name, null, Unit.PIECE, new BigDecimal(quantity), new BigDecimal(price),
                null, null, false, null, null), ownerId);
    }

    /** A «% від кошторису» surcharge — the case a second arithmetic would get wrong. */
    private void addTotalPercent(String name, String percent) {
        estimateService.addItem(parentId, new EstimateItemRequest(
                ItemType.WORK, name, null, Unit.PERCENT, new BigDecimal(percent), BigDecimal.ZERO,
                null, null, false, PercentBaseKind.TOTAL, null), ownerId);
    }

    private EstimateResponse duplicateWith(String percent, boolean discount) {
        return estimateService.duplicate(parentId,
                new EstimateDuplicateRequest(null, new BigDecimal(percent), discount, null), ownerId);
    }

    private void sign(UUID estimateId) {
        jdbc.update("UPDATE estimates SET status = 'SIGNED', signed_at = now() WHERE id = ?", estimateId);
    }

    private CrewMarginResponse panelMarginOf(UUID estimateId) {
        ObjectEconomyResponse economy = economyService.economy(projectId, ownerId);
        return economy.estimates().stream()
                .filter(p -> p.id().equals(estimateId))
                .map(SignedEstimatePanelResponse::crewMargin)
                .findFirst().orElse(null);
    }

    // --- the arithmetic --------------------------------------------------------------------

    @Test
    void ordinaryLinesReportWhatTheCrewGetsAndWhatIsLeft() {
        addWork("Штукатурка", "100", "200");   // crew 20 000
        addWork("Шпаклювання", "100", "100");  // crew 10 000

        EstimateResponse copy = duplicateWith("20", false);

        assertThat(copy.total()).isEqualByComparingTo("36000");   // +20 %
        assertThat(copy.crewMargin()).isNotNull();
        assertThat(copy.crewMargin().crewTotal()).isEqualByComparingTo("30000");
        assertThat(copy.crewMargin().margin()).isEqualByComparingTo("6000");
        assertThat(copy.crewMargin().unpricedCount()).isZero();
    }

    /**
     * A «% від кошторису» line is the reason the crew view runs through {@code EstimateMath} rather
     * than through a subtraction of its own: the surcharge measures the subtotal, so it has to be
     * re-measured against the CREW's subtotal, not scaled.
     */
    @Test
    void aPercentOfTheEstimateIsReMeasuredAgainstTheCrewSubtotal() {
        addWork("Штукатурка", "100", "200");      // crew 20 000
        addTotalPercent("Непередбачені", "10");   // crew 2 000 → crew total 22 000

        EstimateResponse copy = duplicateWith("50", false);

        // Client: 100 × 300 = 30 000, +10 % = 33 000. Crew: 22 000. Margin: 11 000.
        assertThat(copy.total()).isEqualByComparingTo("33000");
        assertThat(copy.crewMargin().crewTotal()).isEqualByComparingTo("22000");
        assertThat(copy.crewMargin().margin()).isEqualByComparingTo("11000");
    }

    /**
     * A line added to the copy afterwards has NO crew price, and the honest answer is that we do not
     * know whether the crew is paid for it. It contributes zero and is reported separately —
     * V85 used to call it «whole line margin», which is the flattering arithmetic that got the
     * object's «Прибуток» hidden in the first place.
     */
    @Test
    void aLineAddedAfterwardsContributesNothingAndIsNamed() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);

        estimateService.addItem(copy.id(), new EstimateItemRequest(
                ItemType.WORK, "Додаткова робота", null, Unit.M2, new BigDecimal("10"),
                new BigDecimal("500"), null, null, false, null, null), ownerId);

        EstimateResponse after = estimateService.get(copy.id(), ownerId);
        assertThat(after.crewMargin().margin()).isEqualByComparingTo("4000"); // unchanged by the 5 000
        assertThat(after.crewMargin().unpricedCount()).isEqualTo(1);
        assertThat(after.crewMargin().unpricedTotal()).isEqualByComparingTo("5000");
    }

    /**
     * THE PARITY FIXTURE, asserted to the same two figures by the PWA's crewMargin.test.ts:
     * an m2 line, a «% від кошторису» surcharge, and a line added afterwards with no crew price.
     * The mirror must be changed in the same commit as this test.
     *
     * <p>Note what the unpriced line does: it contributes nothing to the MARGIN, yet it sits inside
     * the surcharge's base in both views — its own 5 000 and the 500 the percentage adds on top of
     * it appear on both sides and cancel. «Contributes zero» is about the DIFFERENCE, not about the
     * crew total.</p>
     */
    @Test
    void theParityFixtureAgreesWithThePwa() {
        addWork("Штукатурка", "100", "200");
        addTotalPercent("Непередбачені", "10");
        EstimateResponse copy = duplicateWith("50", false);
        estimateService.addItem(copy.id(), new EstimateItemRequest(
                ItemType.WORK, "Додана робота", null, Unit.M2, new BigDecimal("10"),
                new BigDecimal("500"), null, null, false, null, null), ownerId);

        CrewMarginResponse margin = estimateService.get(copy.id(), ownerId).crewMargin();

        assertThat(margin.crewTotal()).isEqualByComparingTo("27500");
        assertThat(margin.margin()).isEqualByComparingTo("11000");
        assertThat(margin.unpricedCount()).isEqualTo(1);
        assertThat(margin.unpricedTotal()).isEqualByComparingTo("5000");
    }

    /** A price raised by hand after duplicating is real margin — the copy is the client's sheet. */
    @Test
    void aPriceEditedAfterDuplicatingMovesTheMargin() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        UUID lineId = copy.items().get(0).id();

        estimateService.updateItem(copy.id(), lineId, new EstimateItemRequest(
                ItemType.WORK, "Штукатурка", null, Unit.M2, new BigDecimal("100"),
                new BigDecimal("300"), null, null, false, null, null), ownerId);

        EstimateResponse after = estimateService.get(copy.id(), ownerId);
        assertThat(after.crewMargin().crewTotal()).isEqualByComparingTo("20000");
        assertThat(after.crewMargin().margin()).isEqualByComparingTo("10000");
    }

    /** A DISCOUNT duplicate is a cheaper offer to the client, not a crew sheet — no margin at all. */
    @Test
    void aDiscountDuplicateReportsNothing() {
        addWork("Штукатурка", "100", "200");

        assertThat(duplicateWith("15", true).crewMargin()).isNull();
    }

    @Test
    void anOrdinaryEstimateReportsNothing() {
        addWork("Штукатурка", "100", "200");

        assertThat(estimateService.get(parentId, ownerId).crewMargin()).isNull();
    }

    /**
     * The margin survives the parent being deleted. It is computed from the copy's own lines —
     * the whole reason V85 stores the crew price per line instead of one percent per estimate.
     */
    @Test
    void theMarginSurvivesTheParentBeingDeleted() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        estimateService.delete(parentId, ownerId);

        assertThat(estimateService.get(copy.id(), ownerId).crewMargin().margin())
                .isEqualByComparingTo("4000");
    }

    // --- the trap --------------------------------------------------------------------------

    /**
     * <b>The one that would corrupt a client's estimate.</b> {@code EstimateMath.recalculate} writes
     * {@code lineTotal} into whatever it is given; reading the margin must therefore leave the
     * stored rows untouched, or a flush would persist the CREW's prices into the signed sheet.
     */
    @Test
    void readingTheMarginDoesNotTouchTheStoredLines() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());

        List<java.util.Map<String, Object>> before = jdbc.queryForList(
                "SELECT unit_price, line_total FROM estimate_items WHERE estimate_id = ? ORDER BY sort_order",
                copy.id());

        economyService.economy(projectId, ownerId);
        estimateService.get(copy.id(), ownerId);

        assertThat(jdbc.queryForList(
                "SELECT unit_price, line_total FROM estimate_items WHERE estimate_id = ? ORDER BY sort_order",
                copy.id()))
                .as("the client's own sheet, after two margin reads")
                .isEqualTo(before);
    }

    /**
     * The crew margin rides the SAME soft gate as payments — one tier, one check, no 403 of its
     * own. What is asserted is the COUPLING, not a plan: {@code Feature.OBJECT_ECONOMY} is
     * temporarily granted to FREE (a business decision recorded on {@code Plan.FREE}), so today a
     * FREE master legitimately sees both. When that grant ends, both must disappear together —
     * and this fails if only one of them does.
     */
    @Test
    void theCrewMarginIsGatedTogetherWithPayments() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());
        jdbc.update("UPDATE users SET plan = 'FREE' WHERE id = ?", ownerId);

        ObjectEconomyResponse economy = economyService.economy(projectId, ownerId);

        assertThat(economy.estimates()).as("panels are FREE-visible regardless").isNotEmpty();
        assertThat(panelMarginOf(copy.id()) == null)
                .as("the crew margin must be present exactly when payments are")
                .isEqualTo(economy.payments() == null);
    }

    // --- the percent beside the amount -------------------------------------------------------

    /**
     * The panel prints the percent the master TYPED. It used to derive it as
     * {@code amount / (works + materials)}, which is the wrong base — a «% від кошторису» line is
     * measured against its OWN TYPE's subtotal — so a discount typed as 15 % over 31 829 ₴ of works
     * printed as «14,776%» once the estimate also carried materials. Reported from live data.
     */
    @Test
    void thePanelReportsThePercentTheMasterTypedNotOneDerivedFromTheWrongBase() {
        addWork("Штукатурка", "100", "200");
        addMaterial("Профіль", "10", "50");
        addTotalPercent("Знижка", "-15");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());

        SignedEstimatePanelResponse panel = economyService.economy(projectId, ownerId).estimates()
                .stream().filter(p -> p.id().equals(copy.id())).findFirst().orElseThrow();

        assertThat(panel.discountRate()).isEqualByComparingTo("-15");
        assertThat(panel.markupRate()).as("no markup line in this estimate").isNull();
    }

    /** Two «% від кошторису» lines at different percents: one figure would be a number nobody's
     *  estimate carries, so none is sent and the screen shows the amount alone. */
    @Test
    void twoPercentLinesThatDisagreeReportNoRate() {
        addWork("Штукатурка", "100", "200");
        addTotalPercent("Непередбачені", "10");
        addTotalPercent("Доставка", "5");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());

        SignedEstimatePanelResponse panel = economyService.economy(projectId, ownerId).estimates()
                .stream().filter(p -> p.id().equals(copy.id())).findFirst().orElseThrow();

        assertThat(panel.markupRate()).isNull();
    }

    // --- the economy panel and the acts ------------------------------------------------------

    @Test
    void theSignedPanelCarriesTheSameFigures() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());

        CrewMarginResponse margin = panelMarginOf(copy.id());
        assertThat(margin).isNotNull();
        assertThat(margin.crewTotal()).isEqualByComparingTo("20000");
        assertThat(margin.margin()).isEqualByComparingTo("4000");
        assertThat(margin.marginAccepted()).isEqualByComparingTo("0");
    }

    /**
     * «З прийнятого актами» counts only SIGNED acts, and only over lines that have a crew price —
     * a narrower set than «Прийнято актами», which also counts off-estimate ADDITIONAL lines. Those
     * were never copy lines, so no crew price for them exists anywhere.
     */
    @Test
    void onlySignedActsContributeToTheAcceptedMargin() {
        addWork("Штукатурка", "100", "200");
        EstimateResponse copy = duplicateWith("20", false);
        sign(copy.id());
        UUID lineId = copy.items().get(0).id();

        actWith("SIGNED", copy.id(), lineId, "40", "240");  // (240 − 200) × 40 = 1 600
        actWith("DRAFT", copy.id(), lineId, "10", "240");   // not accepted by anyone yet

        assertThat(panelMarginOf(copy.id()).marginAccepted()).isEqualByComparingTo("1600");
    }

    private void actWith(String status, UUID estimateId, UUID lineId, String quantity, String price) {
        UUID actId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'INTERIM', ?, current_date, current_date, current_date, now(), now())
                """, actId, ownerId, projectId, nextActNumber++, status);
        jdbc.update("""
                INSERT INTO work_act_item (id, work_act_id, estimate_item_id, estimate_id, type, name,
                                           unit, unit_price, quantity, line_total, cumulative_before, sort_order)
                VALUES (?, ?, ?, ?, 'WORK', 'Штукатурка', 'M2', ?::numeric, ?::numeric,
                        ?::numeric * ?::numeric, 0, 0)
                """, UUID.randomUUID(), actId, lineId, estimateId, price, quantity, price, quantity);
    }
}
