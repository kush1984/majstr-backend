package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActReceiptRequest;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.WorkActReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two guards on an act's receipts that nothing else pins: what a SENT act may become (review B-28)
 * and when the photo behind a deleted receipt is actually destroyed (B-25).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkActReceiptGuardsTest {

    private static final UUID ACT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID RECEIPT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();

    @Mock private WorkActReceiptRepository receiptRepository;
    @Mock private WorkActService actService;
    @Mock private com.majstr.backend.storage.StorageService storage;
    @Mock private com.majstr.backend.service.importer.ActReceiptExtractor recognizer;
    @Mock private ProjectPhotoService photoService;
    @Mock private com.majstr.backend.service.fiscal.FiscalQrReceiptReader qrReader;
    @Mock private ReceiptIdentityIndex identityIndex;
    @Mock private WorkActReceiptCreator creator;
    @Mock private StorageCleanup cleanup;

    @InjectMocks private WorkActReceiptService service;

    /**
     * The client is holding a link he can sign at any second, and a signature over an unpriced
     * receipt is refused — so zeroing one hands him an error that is the master's to fix and that
     * nothing on his screen explains.
     */
    @Test
    void aSentActRefusesToHaveAReceiptPricedBackToZero() {
        given(WorkActStatus.SENT, "1200.00");

        assertThatThrownBy(() -> service.update(ACT, RECEIPT, OWNER, request("0")))
                .isInstanceOf(WorkActValidationException.class)
                .hasFieldOrPropertyWithValue("code", "WORK_ACT_RECEIPT_UNPRICED");
    }

    /** On a DRAFT nothing is at stake: the paper is saved first and priced later, by design. */
    @Test
    void aDraftActMayStillHoldAnUnpricedReceipt() {
        WorkActReceipt receipt = given(WorkActStatus.DRAFT, "1200.00");

        service.update(ACT, RECEIPT, OWNER, request("0"));

        assertThat(receipt.getAmount()).isEqualByComparingTo("0");
    }

    /** A SENT act is not frozen — it just may not become unsignable. A real figure is fine. */
    @Test
    void aSentActStillAcceptsARealFigure() {
        WorkActReceipt receipt = given(WorkActStatus.SENT, "1200.00");

        service.update(ACT, RECEIPT, OWNER, request("1350.40"));

        assertThat(receipt.getAmount()).isEqualByComparingTo("1350.40");
    }

    /**
     * B-25: the photo used to be destroyed BEFORE the row was removed, so anything that rolled the
     * transaction back left a receipt pointing at a file we had already deleted. Storage is never
     * touched inline any more — the cleanup runs after the commit.
     */
    @Test
    void deletingAReceiptDestroysThePhotoOnlyAfterTheRowIsGone() throws Exception {
        WorkActReceipt receipt = given(WorkActStatus.DRAFT, "500.00");

        service.delete(ACT, RECEIPT, OWNER);

        verify(receiptRepository).delete(receipt);
        verify(cleanup).afterCommit("act-receipts/x.jpg");
        verify(storage, never()).delete(any());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private WorkActReceipt given(WorkActStatus status, String amount) {
        Project project = Project.builder().id(PROJECT).build();
        WorkAct act = WorkAct.builder().id(ACT).project(project).status(status)
                .kind(WorkActKind.INTERIM).number("1").build();
        WorkActReceipt receipt = WorkActReceipt.builder()
                .id(RECEIPT).workAct(act).label("Епіцентр")
                .amount(new BigDecimal(amount)).returnedAmount(BigDecimal.ZERO)
                .storageKey("act-receipts/x.jpg").build();
        when(actService.loadOwned(ACT, OWNER)).thenReturn(act);
        when(receiptRepository.findByIdAndWorkActId(RECEIPT, ACT)).thenReturn(Optional.of(receipt));
        when(identityIndex.forProject(PROJECT, List.of()))
                .thenReturn(new ReceiptIdentityIndex.Twins(List.of(), List.of(), Map.of()));
        return receipt;
    }

    private WorkActReceiptRequest request(String amount) {
        return new WorkActReceiptRequest("Епіцентр", new BigDecimal(amount), BigDecimal.ZERO,
                LocalDate.of(2026, 9, 1), null, null);
    }
}
