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
        when(receiptRepository.save(any())).thenAnswer(i -> i.getArgument(0));

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
        when(receiptRepository.findById(id)).thenReturn(Optional.of(receipt(id, "Епіцентр", "480.00")));

        ProjectReceiptResponse replay = service.add(PROJECT, OWNER, id, file(), "Епіцентр",
                new BigDecimal("480.00"), null);

        assertThat(replay.id()).isEqualTo(id);
        verify(receiptRepository, never()).save(any());
        verifyNoInteractions(storage);
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

    // ---- helpers ----------------------------------------------------------

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
