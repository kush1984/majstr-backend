package com.majstr.backend.service;

import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.exception.ProjectReceiptValidationException;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.service.fiscal.FiscalQrService;
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
    @Mock private FiscalQrService fiscalQr;
    /** The transactional half of a create lives in its own bean, so the duplicate-key recovery can
     *  re-read the winner's row in a transaction the failed insert has not poisoned. */
    @Mock private ProjectReceiptCreator creator;

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
        ProjectReceipt first = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        first.setFiscalFn("4000123456");
        first.setFiscalId("77");
        first.setCreatedAt(Instant.parse("2026-09-08T10:00:00Z"));
        ProjectReceipt second = receipt(UUID.randomUUID(), "Епіцентр", "483.50");
        second.setFiscalFn("4000123456");
        second.setFiscalId("77");
        second.setCreatedAt(Instant.parse("2026-09-08T10:05:00Z"));
        // Newest first, the way the repository actually answers.
        when(receiptRepository.findByProjectIdNewestFirst(PROJECT)).thenReturn(List.of(second, first));

        ProjectReceiptsResponse list = service.list(PROJECT, OWNER);

        assertThat(list.items()).extracting(ProjectReceiptResponse::id)
                .containsExactly(second.getId(), first.getId());
        assertThat(list.items().get(0).duplicate()).isTrue();
        assertThat(list.items().get(1).duplicate()).isFalse();
        // Both still count: the master decides, the server only warns.
        assertThat(list.reimbursableTotal()).isEqualByComparingTo("967.00");
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
        return ProjectReceiptResponse.from(r, false);
    }

    private void owned() {
        when(projectService.loadOwned(PROJECT, OWNER))
                .thenReturn(Project.builder().id(PROJECT).build());
    }

    private void stored() throws IOException {
        when(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new StoredObject("object-receipts/x.jpg", JPEG.length, "image/jpeg"));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", JPEG);
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
