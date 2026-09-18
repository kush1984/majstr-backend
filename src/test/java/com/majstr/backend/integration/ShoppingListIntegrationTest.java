package com.majstr.backend.integration;

import com.majstr.backend.dto.CalculatedMaterialRow;
import com.majstr.backend.dto.ShoppingListItemRequest;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListItemUpdateRequest;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.ShoppingListItemSource;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ShoppingListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V126 — the shopping list, and above all the two rules that decide whether the master buys the
 * right amount of material or twice as much.
 *
 * <p>Both fail SILENTLY: nothing throws, no log line appears, the list simply carries a number
 * that is wrong in the master's favour on paper and against him at the till. And both are only
 * observable against a real database — the partial unique index, the generated {@code dedup_key},
 * {@code NULLS NOT DISTINCT} and the {@code ON DELETE SET NULL} behaviour are all schema, not
 * service code.</p>
 */
class ShoppingListIntegrationTest extends IntegrationTestBase {

    private static final String PUTTY = "Шпаклівка фінішна";

    /** A name the shipped dictionary does not carry — the uniqueness test needs a free one. */
    private static final String UNSHIPPED = "Матеріал якого не існує";

    @Autowired JdbcTemplate jdbc;
    @Autowired ShoppingListService shoppingListService;
    @Autowired ProjectService projectService;
    @Autowired EstimateService estimateService;

    private UUID ownerId;
    private UUID projectId;
    private UUID estimateA;
    private UUID estimateB;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        estimateA = insertEstimate("Кошторис A");
        estimateB = insertEstimate("Кошторис B");
    }

    private UUID insertEstimate(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, ?, 'DRAFT')
                """, id, projectId, title);
        return id;
    }

    private CalculatedMaterialRow row(String name, String qty) {
        return new CalculatedMaterialRow(null, name, Unit.PIECE, new BigDecimal(qty), null);
    }

    private ShoppingListItemResponse only(ShoppingListResponse list, String name) {
        List<ShoppingListItemResponse> matching = list.items().stream()
                .filter(i -> i.name().equals(name))
                .toList();
        assertThat(matching).as("rows named %s", name).hasSize(1);
        return matching.get(0);
    }

    // --- rule 1: a recalculation replaces its own contribution -----------------------------

    @Test
    void recalculatingTheSameEstimateDoesNotDoubleTheQuantity() {
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));

        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("12");
    }

    @Test
    void recalculatingOneEstimateLeavesAnotherEstimateAndHandWrittenRowsAlone() {
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.applyCalculated(projectId, ownerId, estimateB, List.of(row("Профіль CD", "40")));
        shoppingListService.addManual(projectId, ownerId, new ShoppingListItemRequest(
                null, "Саморізи 25 мм", Unit.PIECE, new BigDecimal("1000"), null), null);

        // A's line is gone entirely on the re-run: only A's own row may disappear with it.
        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of());

        assertThat(after.items()).extracting(ShoppingListItemResponse::name)
                .containsExactlyInAnyOrder("Профіль CD", "Саморізи 25 мм");
    }

    // --- rule 2: a settled row is never modified -------------------------------------------

    @Test
    void aBoughtRowKeepsItsQuantityAndTheSurplusBecomesItsOwnRow() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        UUID boughtId = only(first, PUTTY).id();
        shoppingListService.setBought(projectId, ownerId, boughtId, true);

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "18")));

        List<ShoppingListItemResponse> putty = after.items().stream()
                .filter(i -> i.name().equals(PUTTY))
                .toList();
        assertThat(putty).hasSize(2);
        assertThat(putty).filteredOn(ShoppingListItemResponse::bought)
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.id()).isEqualTo(boughtId);
                    assertThat(i.quantity()).isEqualByComparingTo("12");
                });
        // Only the difference: «ще 6», not a fresh 18 the master would buy on top of his 12.
        assertThat(putty).filteredOn(i -> !i.bought())
                .singleElement()
                .satisfies(i -> assertThat(i.quantity()).isEqualByComparingTo("6"));
    }

    @Test
    void clearingBoughtRowsHidesThemAndTheMaterialIsNotOfferedAgain() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);
        shoppingListService.clearBought(projectId, ownerId);

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));

        // The cleared row still counts as settled — otherwise the master buys the same putty twice.
        assertThat(after.items()).extracting(ShoppingListItemResponse::name).doesNotContain(PUTTY);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM shopping_list_item i
                JOIN shopping_list l ON l.id = i.shopping_list_id
                WHERE l.project_id = ? AND i.cleared_at IS NOT NULL
                """, Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void aHandEditedQuantityIsNotOverwrittenByARecalculation() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.update(projectId, ownerId, only(first, PUTTY).id(),
                new ShoppingListItemUpdateRequest(new BigDecimal("9"), null, null, null));

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "20")));

        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("9");
        assertThat(only(after, PUTTY).edited()).isTrue();
    }

    @Test
    void aHandEditedRowIsOfferedTheNewFigureInsteadOfBeingLeftInTheDark() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        UUID itemId = only(first, PUTTY).id();
        shoppingListService.update(projectId, ownerId, itemId,
                new ShoppingListItemUpdateRequest(new BigDecimal("9"), null, null, null));

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "20")));

        // His 9 still stands, but the 20 is parked where he can see it and take it with one tap.
        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("9");
        assertThat(only(after, PUTTY).suggestedQuantity()).isEqualByComparingTo("20");

        ShoppingListItemResponse taken = shoppingListService.update(projectId, ownerId, itemId,
                new ShoppingListItemUpdateRequest(null, null, null,
                        ShoppingListItemUpdateRequest.Suggestion.ACCEPT));

        assertThat(taken.quantity()).isEqualByComparingTo("20");
        assertThat(taken.suggestedQuantity()).isNull();
        // Taking our figure hands the row back: the next run owns it again, no second offer.
        assertThat(taken.edited()).isFalse();
        assertThat(only(shoppingListService.applyCalculated(
                projectId, ownerId, estimateA, List.of(row(PUTTY, "24"))), PUTTY))
                .satisfies(i -> {
                    assertThat(i.quantity()).isEqualByComparingTo("24");
                    assertThat(i.suggestedQuantity()).isNull();
                });
    }

    @Test
    void keepingHisOwnNumberClearsTheOfferWithoutTouchingTheQuantity() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        UUID itemId = only(first, PUTTY).id();
        shoppingListService.update(projectId, ownerId, itemId,
                new ShoppingListItemUpdateRequest(new BigDecimal("9"), null, null, null));
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "20")));

        ShoppingListItemResponse kept = shoppingListService.update(projectId, ownerId, itemId,
                new ShoppingListItemUpdateRequest(null, null, null,
                        ShoppingListItemUpdateRequest.Suggestion.KEEP_MINE));

        assertThat(kept.quantity()).isEqualByComparingTo("9");
        assertThat(kept.suggestedQuantity()).isNull();
        assertThat(kept.edited()).isTrue();

        // Answering an offer that is already gone is a no-op, not a failure: the tap may have been
        // queued offline at the merchant and replayed long after the question was settled.
        assertThat(shoppingListService.update(projectId, ownerId, itemId,
                new ShoppingListItemUpdateRequest(null, null, null,
                        ShoppingListItemUpdateRequest.Suggestion.ACCEPT)).quantity())
                .isEqualByComparingTo("9");
    }

    @Test
    void anOfferDoesNotOutliveTheCalculationThatMadeIt() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.update(projectId, ownerId, only(first, PUTTY).id(),
                new ShoppingListItemUpdateRequest(new BigDecimal("9"), null, null, null));
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "20")));

        // The position left the estimate: his row stays (it is his), the stale 20 does not.
        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of());

        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("9");
        assertThat(only(after, PUTTY).suggestedQuantity()).isNull();
    }

    @Test
    void theDeltaRowSaysItIsATopUpAndTheRowItToppedUpDoesNot() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "18")));

        // «ще 6» rather than a bare «6», which beside a bought 12 reads as our arithmetic slipping.
        assertThat(after.items()).filteredOn(i -> i.name().equals(PUTTY) && !i.bought())
                .singleElement().satisfies(i -> assertThat(i.topUp()).isTrue());
        assertThat(after.items()).filteredOn(i -> i.name().equals(PUTTY) && i.bought())
                .singleElement().satisfies(i -> assertThat(i.topUp()).isFalse());
    }

    @Test
    void aClearedRowStillMakesTheNextOneATopUp() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);
        shoppingListService.clearBought(projectId, ownerId);

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "18")));

        // The 12 is off the screen but not out of the arithmetic — the 6 is still «ще».
        assertThat(only(after, PUTTY).topUp()).isTrue();
    }

    @Test
    void untickingAfterATopUpMergesTheRowsInsteadOfFailing() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        UUID boughtId = only(first, PUTTY).id();
        shoppingListService.setBought(projectId, ownerId, boughtId, true);
        // The bigger figure parks «ще 6» beside the bought 12 — by design, two rows on one key.
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "18")));

        // «я його все ж не купив». `ux_shopping_list_item_open` admits ONE open row per key, so this
        // used to be an unmapped 500 and the master was told his list did not save.
        shoppingListService.setBought(projectId, ownerId, boughtId, false);

        ShoppingListResponse after = shoppingListService.get(projectId, ownerId);
        ShoppingListItemResponse merged = only(after, PUTTY);
        assertThat(merged.id()).as("the row he tapped survives").isEqualTo(boughtId);
        assertThat(merged.bought()).isFalse();
        // Together they are the whole demand again — which is what the top-up existed to complete.
        assertThat(merged.quantity()).isEqualByComparingTo("18");
        assertThat(merged.topUp()).as("nothing settled is left for it to top up").isFalse();
    }

    // --- deleting the source --------------------------------------------------------------

    /**
     * `source_estimate_id` is ON DELETE SET NULL, Postgres runs a SET NULL as an UPDATE, and a
     * CALCULATOR row with no estimate fails `shopping_list_item_calculated_source_check` — so
     * deleting any estimate ever sent to the list raised `check_violation` and the master was told
     * his estimate did not delete.
     */
    @Test
    void deletingAnEstimateKeepsWhatHeBought_andDropsWhatNobodyTouched() {
        ShoppingListResponse first = shoppingListService.applyCalculated(projectId, ownerId, estimateA,
                List.of(row(PUTTY, "12"), row("Профіль CD", "40")));
        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);

        estimateService.delete(estimateA, ownerId);

        ShoppingListResponse after = shoppingListService.get(projectId, ownerId);
        // What he bought stays on his list; an untouched row is worth nothing once its source is gone.
        assertThat(after.items()).extracting(ShoppingListItemResponse::name).containsExactly(PUTTY);
        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("12");
        // And it is now HIS row: nothing can recalculate it any more.
        assertThat(only(after, PUTTY).source()).isEqualTo(ShoppingListItemSource.MANUAL);
    }

    /** A hand-edited row survives the same way — it carries a figure nobody else typed. */
    @Test
    void deletingAnEstimateKeepsAHandCorrectedRow() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.update(projectId, ownerId, only(first, PUTTY).id(),
                new ShoppingListItemUpdateRequest(new BigDecimal("9"), null, null, null));

        estimateService.delete(estimateA, ownerId);

        ShoppingListResponse after = shoppingListService.get(projectId, ownerId);
        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("9");
        assertThat(only(after, PUTTY).source()).isEqualTo(ShoppingListItemSource.MANUAL);
    }

    /**
     * The object's own delete reaches the rows down TWO sibling branches of one cascade — the list
     * (CASCADE) and `estimates` (SET NULL) — and Postgres does not define which fires first, so the
     * SET NULL could reach a row whose list was still there and fail the same CHECK.
     */
    @Test
    void deletingTheObjectTakesItsShoppingRowsWithIt() {
        shoppingListService.applyCalculated(projectId, ownerId, estimateA,
                List.of(row(PUTTY, "12"), row("Профіль CD", "40")));

        projectService.delete(projectId, ownerId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopping_list WHERE project_id = ?",
                Integer.class, projectId)).isZero();
    }

    // --- schema -----------------------------------------------------------------------------

    // --- the unsigned-source hint ------------------------------------------------------------

    /**
     * A hint, never a gate. The quantities were calculated from an estimate the client has not
     * signed, so they can still move — the master is told, and decides for himself whether to buy.
     */
    @Test
    void theListSaysWhenItsFiguresComeFromAnEstimateThatIsNotSignedYet() {
        ShoppingListResponse fromDraft = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        assertThat(fromDraft.sourceEstimateUnsigned()).isTrue();

        jdbc.update("UPDATE estimates SET status = 'SIGNED' WHERE id = ?", estimateA);

        assertThat(shoppingListService.get(projectId, ownerId).sourceEstimateUnsigned()).isFalse();
    }

    /** A hand-written row is nobody's calculation, so it never raises the hint. */
    @Test
    void aHandWrittenRowNeverRaisesTheHint() {
        shoppingListService.addManual(projectId, ownerId,
                new ShoppingListItemRequest(null, PUTTY, Unit.PIECE, new BigDecimal("3"), null), null);

        assertThat(shoppingListService.get(projectId, ownerId).sourceEstimateUnsigned()).isFalse();
    }

    @Test
    void twoMaterialsWithTheSameNameAndNoSpecAreRejected() {
        jdbc.update("INSERT INTO material (id, name, unit) VALUES (?, ?, 'KG')",
                UUID.randomUUID(), UNSHIPPED);

        // With a plain UNIQUE, Postgres treats each NULL spec as distinct and BOTH rows insert —
        // the "one row per material" guarantee would break exactly where most materials live.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO material (id, name, unit) VALUES (?, ?, 'KG')",
                UUID.randomUUID(), UNSHIPPED))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anObjectHasAtMostOneShoppingList() {
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "1")));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO shopping_list (id, project_id) VALUES (?, ?)",
                UUID.randomUUID(), projectId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnEstimateLineKeepsTheShoppingRow() {
        UUID itemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, name, unit, quantity, unit_price,
                                            line_total, type, sort_order)
                VALUES (?, ?, 'Шпаклювання', 'M2', 10, 100, 1000, 'WORK', 0)
                """, itemId, estimateA);

        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(
                new CalculatedMaterialRow(null, PUTTY, Unit.KG, new BigDecimal("25"), itemId)));

        jdbc.update("DELETE FROM estimate_items WHERE id = ?", itemId);

        ShoppingListResponse after = shoppingListService.get(projectId, ownerId);
        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("25");
    }

    @Test
    void aCalculatedRowMustCarryItsEstimate() {
        UUID listId = UUID.randomUUID();
        jdbc.update("INSERT INTO shopping_list (id, project_id) VALUES (?, ?)", listId, projectId);

        // Without the estimate a re-run cannot replace the row, only duplicate it.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO shopping_list_item (id, shopping_list_id, name, unit, quantity, source)
                VALUES (?, ?, ?, 'KG', 25, 'CALCULATOR')
                """, UUID.randomUUID(), listId, PUTTY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- lifecycle --------------------------------------------------------------------------

    @Test
    void finishingTheObjectArchivesTheListAndReopeningBringsItBack() {
        shoppingListService.applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));

        projectService.updateStatus(projectId, ProjectStatus.COMPLETED, ownerId);
        assertThat(shoppingListService.get(projectId, ownerId).archivedAt()).isNotNull();
        // The card disappears; the content does not.
        assertThat(shoppingListService.get(projectId, ownerId).items()).hasSize(1);
        assertThat(shoppingListService.summaries(ownerId))
                .extracting(s -> s.projectId()).doesNotContain(projectId);

        projectService.updateStatus(projectId, ProjectStatus.IN_PROGRESS, ownerId);
        assertThat(shoppingListService.get(projectId, ownerId).archivedAt()).isNull();
        assertThat(shoppingListService.summaries(ownerId))
                .extracting(s -> s.projectId()).contains(projectId);
    }

    @Test
    void theHomeCardDropsAnObjectOnceEverythingIsBought() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        assertThat(shoppingListService.summaries(ownerId))
                .extracting(s -> s.projectId()).contains(projectId);

        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);

        assertThat(shoppingListService.summaries(ownerId))
                .extracting(s -> s.projectId()).doesNotContain(projectId);
    }

    // --- offline ----------------------------------------------------------------------------

    @Test
    void aReplayedOfflineAddReturnsTheSameRowInsteadOfASecondOne() {
        UUID clientId = UUID.randomUUID();
        ShoppingListItemRequest req = new ShoppingListItemRequest(
                null, "Клей Ceresit", Unit.PIECE, new BigDecimal("4"), null);

        ShoppingListItemResponse first = shoppingListService.addManual(projectId, ownerId, req, clientId);
        ShoppingListItemResponse replay = shoppingListService.addManual(projectId, ownerId, req, clientId);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(shoppingListService.get(projectId, ownerId).items()).hasSize(1);
        assertThat(first.source()).isEqualTo(ShoppingListItemSource.MANUAL);
    }

    /**
     * The replay lookup is scoped to THIS list (review item B-13). It used to be an unscoped
     * {@code findById}, which answered about rows on other objects' lists: the id exists, so the
     * create was treated as a replay of something that never happened here.
     *
     * <p>And scoping the READ alone is not enough, which is the sharper half: the client's uuid is
     * this table's PRIMARY KEY, so writing it back is a MERGE — a create carrying another list's id
     * would have overwritten that row and moved it here, from a header. The server drops the offered
     * id in that case and authors its own.</p>
     */
    @Test
    void aReplayedAddOnANOTHERObjectIsAFreshRowHere() {
        UUID otherProject = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Другий обʼєкт', 'вул. Інша 2', 'IN_PROGRESS')
                """, otherProject, ownerId);
        UUID clientId = UUID.randomUUID();
        ShoppingListItemRequest req = new ShoppingListItemRequest(
                null, "Клей Ceresit", Unit.PIECE, new BigDecimal("4"), null);

        ShoppingListItemResponse there = shoppingListService
                .addManual(otherProject, ownerId, req, clientId);
        ShoppingListItemResponse here = shoppingListService
                .addManual(projectId, ownerId, req, clientId);

        assertThat(here.id()).as("not a replay: the row lives on another object").isNotEqualTo(there.id());
        assertThat(shoppingListService.get(projectId, ownerId).items()).hasSize(1);
        // The other object still has its own row, on its own list — nothing was moved.
        assertThat(shoppingListService.get(otherProject, ownerId).items())
                .extracting(ShoppingListItemResponse::id).containsExactly(there.id());
    }

    // --- what the master left on a row is his (review item B-14, B-31a) ----------------------

    /**
     * A NOTE keeps the row alive when the position leaves the estimate (review item B-14). Only a
     * hand-typed QUANTITY used to, so «взяти в Епіцентрі, спитати Сергія» vanished the moment the
     * line was removed — silently, and the errand with it.
     */
    @Test
    void aNoteSurvivesTheMaterialLeavingTheEstimate() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        shoppingListService.update(projectId, ownerId, only(first, PUTTY).id(),
                new ShoppingListItemUpdateRequest(null, "взяти в Епіцентрі, спитати Сергія", null, null));

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of());

        ShoppingListItemResponse kept = only(after, PUTTY);
        assertThat(kept.note()).isEqualTo("взяти в Епіцентрі, спитати Сергія");
        // The note says something about the MATERIAL, not about the number, so the calculator still
        // owns the quantity — the row is not `edited`.
        assertThat(kept.edited()).isFalse();
    }

    /**
     * …but not when his own settled purchases already cover the demand: a note does not keep a
     * top-up he no longer needs standing in the list. The asymmetry is deliberate — the schema
     * forbids an open row at 0, so the alternative is a stale figure, and that costs him money.
     */
    @Test
    void aNoteDoesNotKeepATopUpTheMasterNoLongerNeeds() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "18")));
        shoppingListService.setBought(projectId, ownerId, only(first, PUTTY).id(), true);
        ShoppingListResponse withTopUp = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "24")));
        ShoppingListItemResponse topUp = withTopUp.items().stream()
                .filter(i -> i.name().equals(PUTTY) && !i.bought()).findFirst().orElseThrow();
        shoppingListService.update(projectId, ownerId, topUp.id(),
                new ShoppingListItemUpdateRequest(null, "спитати Сергія", null, null));

        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));

        assertThat(after.items()).extracting(ShoppingListItemResponse::id).doesNotContain(topUp.id());
        assertThat(only(after, PUTTY).bought()).as("what he bought stays").isTrue();
    }

    /**
     * Un-buying a CLEARED row unsettles it (review item B-31a). {@code cleared_at} settles a row
     * just as much as {@code bought} does, so leaving it standing kept the row invisible while its
     * quantity still counted as covered: the master un-ticked a material and the next recalculation
     * refused to ask for it.
     */
    @Test
    void untickingAClearedRowBringsItBackAndTheMaterialIsAskedForAgain() {
        ShoppingListResponse first = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "12")));
        UUID itemId = only(first, PUTTY).id();
        shoppingListService.setBought(projectId, ownerId, itemId, true);
        shoppingListService.clearBought(projectId, ownerId);

        shoppingListService.setBought(projectId, ownerId, itemId, false);

        ShoppingListItemResponse back = only(shoppingListService.get(projectId, ownerId), PUTTY);
        assertThat(back.id()).isEqualTo(itemId);
        assertThat(back.bought()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT cleared_at FROM shopping_list_item WHERE id = ?", java.sql.Timestamp.class,
                itemId)).as("no longer hidden").isNull();

        // And it is open again, so a recalculation restates it instead of treating it as covered.
        ShoppingListResponse after = shoppingListService
                .applyCalculated(projectId, ownerId, estimateA, List.of(row(PUTTY, "20")));
        assertThat(only(after, PUTTY).quantity()).isEqualByComparingTo("20");
    }
}
