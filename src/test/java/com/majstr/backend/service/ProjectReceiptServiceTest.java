package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.dto.ReceiptDuplicateRef;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.exception.ProjectReceiptBilledException;
import com.majstr.backend.exception.ProjectReceiptValidationException;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.service.fiscal.FiscalQrReceiptReader;
import com.majstr.backend.service.importer.ActReceiptExtractor;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * «Чеки обʼєкта» (V129). What these pin is the one thing that makes the till two taps: saving a
 * receipt decides NOTHING about whose money it was. The default is «клієнт відшкодовує», so a saved
 * receipt is a receivable and touches no expense journal; only the explicit flip writes money.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectReceiptServiceTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};

    @Mock private ProjectReceiptRepository receiptRepository;
    @Mock private ObjectExpenseRepository expenseRepository;
    @Mock private ProjectService projectService;
    @Mock private StorageService storage;
    @Mock private ActReceiptExtractor recognizer;
    @Mock private FiscalQrReceiptReader qrReader;
    /** Stubbed, not real: it reads BOTH receipt tables (B-04) and its own logic — which of a pair
     *  is «the original», and same-table-first — is pinned by {@code ReceiptIdentityIndexTest}. */
    @Mock private ReceiptIdentityIndex identityIndex;
    /** The transactional half of a create lives in its own bean, so the duplicate-key recovery can
     *  re-read the winner's row in a transaction the failed insert has not poisoned. */
    @Mock private ProjectReceiptCreator creator;
    @Mock private StorageCleanup cleanup;

    @InjectMocks private ProjectReceiptService service;

    @Test
    void photoIsMandatory() {
        owned();

        assertThatThrownBy(() -> service.add(PROJECT, OWNER, null, null, "Епіцентр",
                new BigDecimal("480.00"), LocalDate.of(2026, 9, 8)))
                .isInstanceOf(ProjectReceiptValidationException.class)
                .hasMessage("error.project-receipt.photo-required");

        verifyNoInteractions(storage);
    }

    /** The whole point of the batch: the photo is saved before the sum is known. */
    @Test
    void savesWithNoAmountAndNamesTheReceiptItself() throws IOException {
        owned();
        stored();
        when(receiptRepository.countByProjectId(PROJECT)).thenReturn(2L);
        when(receiptRepository.maxSortOrder(PROJECT)).thenReturn(1);
        attemptWrites();

        ProjectReceiptResponse saved = service.add(PROJECT, OWNER, null, file(), "  ", null, null);

        assertThat(saved.label()).isEqualTo("Чек №3");
        assertThat(saved.amount()).isEqualByComparingTo("0.00");
        assertThat(saved.hasPhoto()).isTrue();
        // Nothing was decided about the money.
        assertThat(saved.reimbursable()).isTrue();
        verifyNoInteractions(expenseRepository);
    }

    /** A retried upload over a weak connection must not bill the same material twice. */
    @Test
    void replayUnderTheSameUuidStoresNothing() throws IOException {
        owned();
        UUID id = UUID.randomUUID();
        when(creator.prepare(PROJECT, OWNER, id))
                .thenReturn(Optional.of(response(receipt(id, "Епіцентр", "480.00"))));

        ProjectReceiptResponse replay = service.add(PROJECT, OWNER, id, file(), "Епіцентр",
                new BigDecimal("480.00"), null);

        assertThat(replay.id()).isEqualTo(id);
        verify(creator, never()).attempt(any(), any(), anyString(), any(), any(), anyString(), anyInt());
        verifyNoInteractions(storage);
    }

    /**
     * The SAME replay, but the two uploads are in flight at once — a weak connection retrying while
     * the first request is still running, so neither sees the other's row on the way in. The loser's
     * insert hits the client-supplied primary key: the row the winner wrote IS the answer, and the
     * photo this attempt had already stored is an orphan nothing would ever reference.
     */
    @Test
    void losingTheRaceOnTheSameUuidAnswersWithTheWinnerAndDropsTheOrphanPhoto() throws IOException {
        owned();
        stored();
        UUID id = UUID.randomUUID();
        when(receiptRepository.countByProjectId(PROJECT)).thenReturn(0L);
        when(receiptRepository.maxSortOrder(PROJECT)).thenReturn(-1);
        when(creator.attempt(any(), any(), anyString(), any(), any(), anyString(), anyInt()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"project_receipt_pkey\""));
        when(creator.replay(id, PROJECT))
                .thenReturn(Optional.of(response(receipt(id, "Епіцентр", "480.00"))));

        ProjectReceiptResponse answered = service.add(PROJECT, OWNER, id, file(), "Епіцентр",
                new BigDecimal("480.00"), LocalDate.of(2026, 9, 8));

        assertThat(answered.id()).isEqualTo(id);
        verify(storage).delete("object-receipts/x.jpg");
    }

    /** Reading the sum off the paper carries no opinion about whose money it is. */
    @Test
    void pricingAReceiptWritesNoExpense() {
        owned();
        ProjectReceipt r = receipt(UUID.randomUUID(), "Епіцентр", "0.00");
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        ProjectReceiptResponse updated = service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), null, null, null));

        assertThat(updated.amount()).isEqualByComparingTo("483.50");
        assertThat(updated.reimbursable()).isTrue();
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void flippingToOwnCostPostsAMaterialsExpense() {
        owned();
        ProjectReceipt r = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        r.setIssuedAt(LocalDate.of(2026, 9, 8));
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));
        UUID expenseId = UUID.randomUUID();
        when(expenseRepository.save(any())).thenAnswer(i -> {
            ObjectExpense e = i.getArgument(0);
            e.setId(expenseId);
            return e;
        });

        ProjectReceiptResponse updated = service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), false, null, null));

        assertThat(updated.reimbursable()).isFalse();
        ArgumentCaptor<ObjectExpense> posted = ArgumentCaptor.forClass(ObjectExpense.class);
        verify(expenseRepository).save(posted.capture());
        assertThat(posted.getValue().getObjectId()).isEqualTo(PROJECT);
        assertThat(posted.getValue().getCategory()).isEqualTo(ExpenseCategory.MATERIALS);
        assertThat(posted.getValue().getSource()).isEqualTo(ExpenseSource.RECEIPT);
        assertThat(posted.getValue().getAmount()).isEqualByComparingTo("483.50");
        assertThat(posted.getValue().getSpentAt()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(r.getExpenseId()).isEqualTo(expenseId);
    }

    @Test
    void flippingBackRemovesTheExpense() {
        owned();
        ProjectReceipt r = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        r.setReimbursable(false);
        UUID expenseId = UUID.randomUUID();
        r.setExpenseId(expenseId);
        ObjectExpense expense = ObjectExpense.builder().id(expenseId).objectId(PROJECT).build();
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));
        when(expenseRepository.findByIdAndObjectId(expenseId, PROJECT)).thenReturn(Optional.of(expense));

        ProjectReceiptResponse updated = service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("483.50"), null, true, null, null));

        assertThat(updated.reimbursable()).isTrue();
        verify(expenseRepository).delete(expense);
        assertThat(r.getExpenseId()).isNull();
    }

    /** Deleting the receipt takes the money it posted with it. */
    @Test
    void deleteAlsoDropsTheExpense() {
        owned();
        ProjectReceipt r = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        r.setReimbursable(false);
        UUID expenseId = UUID.randomUUID();
        r.setExpenseId(expenseId);
        ObjectExpense expense = ObjectExpense.builder().id(expenseId).objectId(PROJECT).build();
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));
        when(expenseRepository.findByIdAndObjectId(expenseId, PROJECT)).thenReturn(Optional.of(expense));

        service.delete(PROJECT, r.getId(), OWNER);

        verify(expenseRepository).delete(expense);
        verify(receiptRepository).delete(r);
        // The paper goes AFTER the row (B-25): inline, a rollback left the receipt claiming a
        // photo we had already destroyed.
        verify(cleanup).afterCommit(r.getStorageKey());
    }

    /**
     * The two totals are separate axes: what the client owes back and what the master paid himself.
     * Folding them together is what the whole «whose money» flag exists to prevent.
     */
    @Test
    void listSplitsTheTwoAxesAndCountsWhatStillNeedsANumber() {
        owned();
        ProjectReceipt reimbursable = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        ProjectReceipt own = receipt(UUID.randomUUID(), "Метизи", "120.00");
        own.setReimbursable(false);
        ProjectReceipt unpriced = receipt(UUID.randomUUID(), "Чек №3", "0.00");
        when(receiptRepository.findByProjectIdNewestFirst(PROJECT))
                .thenReturn(List.of(reimbursable, own, unpriced));

        ProjectReceiptsResponse list = service.list(PROJECT, OWNER);

        assertThat(list.reimbursableTotal()).isEqualByComparingTo("483.50");
        assertThat(list.ownTotal()).isEqualByComparingTo("120.00");
        assertThat(list.unpricedCount()).isEqualTo(1);
    }

    /** A warning on the LATER paper only, and never a block — a shop may reprint a slip. */
    @Test
    void aRepeatedFiscalIdentityFlagsTheSecondReceipt() {
        owned();
        ProjectReceipt first = identified(receipt(UUID.randomUUID(), "Епіцентр", "483.50"),
                "2026-09-08T10:00:00Z");
        ProjectReceipt second = identified(receipt(UUID.randomUUID(), "Епіцентр", "483.50"),
                "2026-09-08T10:05:00Z");
        // Newest first, the way the repository actually answers — and oldest first in the index.
        when(receiptRepository.findByProjectIdNewestFirst(PROJECT)).thenReturn(List.of(second, first));
        twins(List.of(first, second), List.of());

        ProjectReceiptsResponse list = service.list(PROJECT, OWNER);

        assertThat(list.items()).extracting(ProjectReceiptResponse::id)
                .containsExactly(second.getId(), first.getId());
        // The warning NAMES where the twin is, so «схоже на дублікат» became something to act on.
        assertThat(list.items().get(0).duplicateOf())
                .isEqualTo(ReceiptDuplicateRef.object(first.getId(), "Епіцентр"));
        assertThat(list.items().get(1).duplicateOf()).isNull();
        // Both still count: the master decides, the server only warns.
        assertThat(list.reimbursableTotal()).isEqualByComparingTo("967.00");
    }

    /**
     * The pair B-04 exists for: the slip photographed at the till AND attached to an act. Nothing
     * could see it before — the act table had no printed identity at all — and it is the one pair
     * that bills the client twice.
     */
    @Test
    void aPaperAlsoFiledOnAnActPointsAtTheAct() {
        owned();
        ProjectReceipt atTheTill = identified(receipt(UUID.randomUUID(), "Епіцентр", "483.50"),
                "2026-09-08T10:05:00Z");
        WorkActReceipt onTheAct = WorkActReceipt.builder()
                .id(UUID.randomUUID()).label("Цвяхи").amount(new BigDecimal("483.50"))
                .workAct(WorkAct.builder().id(UUID.randomUUID()).number("7").build())
                .fiscalFn("4000123456").fiscalId("77")
                .createdAt(Instant.parse("2026-09-08T09:00:00Z"))
                .build();
        when(receiptRepository.findByProjectIdNewestFirst(PROJECT)).thenReturn(List.of(atTheTill));
        twins(List.of(atTheTill), List.of(onTheAct));

        ProjectReceiptsResponse list = service.list(PROJECT, OWNER);

        assertThat(list.items().getFirst().duplicateOf())
                .isEqualTo(ReceiptDuplicateRef.act(onTheAct.getId(), "Цвяхи", "7"));
    }

    /**
     * A receipt a signed act already billed is OUT of «клієнт відшкодовує» — the ADDENDUM moved that
     * money into «За договором», and a debt shown in two places gets asked for twice. The ROW stays,
     * saying which act took it. This total and {@code sumReimbursable} describe one number on two
     * screens, so they filter identically.
     */
    @Test
    void aReceiptBilledOnAnActLeavesTheReceivableButKeepsItsRow() {
        owned();
        ProjectReceipt open = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        ProjectReceipt billed = receipt(UUID.randomUUID(), "Нова Лінія", "120.00");
        billed.setBilledOnActId(UUID.randomUUID());
        when(receiptRepository.findByProjectIdNewestFirst(PROJECT)).thenReturn(List.of(billed, open));

        ProjectReceiptsResponse list = service.list(PROJECT, OWNER);

        assertThat(list.items()).hasSize(2);
        assertThat(list.reimbursableTotal()).isEqualByComparingTo("483.50"); // the 120 left the axis
    }

    /**
     * The master removed the linked expense from the journal by hand (an older row — the journal now
     * refuses that), so the receipt is left claiming «це моя витрата» with nothing behind it. The
     * next ordinary save carries no opinion about whose money it was: the recognition prefill, a
     * corrected label. It used to resolve «own cost», find no expense and post a SECOND one —
     * putting a cost back into his profit on a save he thought only fixed a name.
     */
    @Test
    void anEditAfterTheExpenseWasDeletedByHandDoesNotPostItAgain() {
        owned();
        ProjectReceipt r = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        r.setReimbursable(false); // «це моя витрата» still stands…
        r.setExpenseId(null);     // …but the row it posted is gone.
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        ProjectReceiptResponse updated = service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр (Позняки)", new BigDecimal("483.50"),
                        LocalDate.of(2026, 9, 8), null, null, null));

        assertThat(updated.reimbursable()).isFalse();
        // And the screen can say so: the claim stands over an expense the economy does not count.
        assertThat(updated.hasExpense()).isFalse();
        verify(expenseRepository, never()).save(any());
    }

    /**
     * Review B-32. A receipt an act has billed to the client is frozen on the MONEY axis: the
     * signature already counts it, and with {@code receipts_to_expenses} off this row is the ONLY
     * record of that cost — so «клієнт відшкодовує» here deletes it and overstates profit by the
     * whole receipt (B-32a).
     */
    @Test
    void aBilledReceiptRefusesTheFlipThatWouldDeleteItsCost() {
        owned();
        ProjectReceipt r = billed(receipt(UUID.randomUUID(), "Епіцентр", "2000.00"));
        r.setReimbursable(false);
        r.setExpenseId(UUID.randomUUID());
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("2000.00"), null, true, null, null)))
                .isInstanceOf(ProjectReceiptBilledException.class)
                .hasMessage("error.project-receipt.billed-on-act");

        verify(expenseRepository, never()).delete(any());
    }

    /** The mirror direction posts a SECOND cost beside the one the act already carries. */
    @Test
    void aBilledReceiptRefusesTheFlipToOwnCostToo() {
        owned();
        ProjectReceipt r = billed(receipt(UUID.randomUUID(), "Епіцентр", "2000.00"));
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("2000.00"), null, false, null, null)))
                .isInstanceOf(ProjectReceiptBilledException.class);

        verify(expenseRepository, never()).save(any());
    }

    /** B-32c: the amount is what the client signed for. */
    @Test
    void aBilledReceiptRefusesARepricing() {
        owned();
        ProjectReceipt r = billed(receipt(UUID.randomUUID(), "Епіцентр", "2000.00"));
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("2500.00"), null, null, null, null)))
                .isInstanceOf(ProjectReceiptBilledException.class);

        assertThat(r.getAmount()).isEqualByComparingTo("2000.00");
    }

    /** B-32b: the delete would take the object's cost record out from under the signature. */
    @Test
    void aBilledReceiptRefusesToBeDeleted() {
        owned();
        ProjectReceipt r = billed(receipt(UUID.randomUUID(), "Епіцентр", "2000.00"));
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.delete(PROJECT, r.getId(), OWNER))
                .isInstanceOf(ProjectReceiptBilledException.class);

        verify(receiptRepository, never()).delete(any());
        verifyNoInteractions(cleanup);
    }

    /**
     * What the PAPER says is not money, and the master must still be able to write it down — the
     * shop's name, the day he bought it, and the fiscal identity a QR read only produces later.
     * Re-sending the `reimbursable` the row already holds is what an ordinary save from a screen
     * that knows the answer looks like, so that is not a flip either.
     */
    @Test
    void aBilledReceiptStillTakesALabelDateAndIdentity() {
        owned();
        ProjectReceipt r = billed(receipt(UUID.randomUUID(), "Чек №1", "2000.00"));
        when(receiptRepository.findByIdAndProjectId(r.getId(), PROJECT)).thenReturn(Optional.of(r));

        ProjectReceiptResponse updated = service.update(PROJECT, r.getId(), OWNER,
                new ProjectReceiptRequest("Епіцентр", new BigDecimal("2000.00"),
                        LocalDate.of(2026, 9, 8), true, "4000123456", "77"));

        assertThat(updated.label()).isEqualTo("Епіцентр");
        assertThat(updated.issuedAt()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(r.getFiscalFn()).isEqualTo("4000123456");
    }

    // ---- helpers ----------------------------------------------------------

    /** Let the creator's {@code attempt} write, answering the way the real one does — so label,
     *  amount and sort order are still the SERVICE's own arithmetic, not the stub's. */
    private void attemptWrites() {
        when(creator.attempt(any(), any(), anyString(), any(), any(), anyString(), anyInt()))
                .thenAnswer(i -> response(ProjectReceipt.builder()
                        .id(UUID.randomUUID())
                        .projectId(i.getArgument(0))
                        .label(i.getArgument(2))
                        .amount(i.getArgument(3))
                        .issuedAt(i.getArgument(4))
                        .storageKey(i.getArgument(5))
                        .sortOrder(i.getArgument(6))
                        .build()));
    }

    private static ProjectReceiptResponse response(ProjectReceipt r) {
        return ProjectReceiptResponse.from(r);
    }

    private void owned() {
        when(projectService.loadOwned(PROJECT, OWNER))
                .thenReturn(Project.builder().id(PROJECT).build());
        // Nothing identified anywhere — the ordinary object, and the shape every test but the
        // duplicate ones needs. A test that cares overrides it with `twins(...)`.
        twins(List.of(), List.of());
    }

    /** One printed identity, shared by every «same paper» fixture below — what the QR read writes. */
    private static ProjectReceipt identified(ProjectReceipt r, String createdAt) {
        r.setFiscalFn("4000123456");
        r.setFiscalId("77");
        r.setCreatedAt(Instant.parse(createdAt));
        return r;
    }

    /** Hand the service a REAL {@link ReceiptIdentityIndex.Twins} over the rows a test set up, so
     *  the matching under assertion is the production one and only the loading is stubbed. */
    private void twins(List<ProjectReceipt> objectRows, List<WorkActReceipt> actRows) {
        when(identityIndex.forProject(any(), any()))
                .thenReturn(new ReceiptIdentityIndex.Twins(objectRows, actRows, Map.of()));
    }

    private void stored() throws IOException {
        when(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new StoredObject("object-receipts/x.jpg", JPEG.length, "image/jpeg"));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG);
    }

    /** Stamped by {@code ActReceiptReconciler} when an act carrying the same paper was signed. */
    private static ProjectReceipt billed(ProjectReceipt r) {
        r.setBilledOnActId(UUID.randomUUID());
        return r;
    }

    private static ProjectReceipt receipt(UUID id, String label, String amount) {
        return ProjectReceipt.builder()
                .id(id)
                .projectId(PROJECT)
                .label(label)
                .amount(new BigDecimal(amount))
                .storageKey("object-receipts/" + id + ".jpg")
                .createdAt(Instant.parse("2026-09-08T10:00:00Z"))
                .build();
    }
}
