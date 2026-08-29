package com.majstr.backend.service;

import com.majstr.backend.dto.ActReceiptRecognizeResponse;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.service.importer.ActReceiptExtractor;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reading a receipt for an ACT is free, and reads three footer fields — nothing else. There used to
 * be a second, PRO-gated pass that read the item table and carried the positions into the act; it
 * was removed on 2026-08-28 («для чого ми зробили щоб позиції переносились?»), so what these pin
 * now is the opposite of the old split: NO gate on this path at all, one flow, and no lookup on the
 * QR path. Reading a receipt INTO AN ESTIMATE is a different feature and keeps its own gate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkActReceiptRecognitionTest {

    private static final UUID ACT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @Mock private WorkActReceiptRepository receiptRepository;
    @Mock private WorkActService actService;
    @Mock private StorageService storage;
    @Mock private ActReceiptExtractor recognizer;
    @Mock private ProjectPhotoService photoService;
    @Mock private com.majstr.backend.service.fiscal.FiscalQrService fiscalQr;

    @InjectMocks private WorkActReceiptService service;

    @Test
    void recognitionIsFree_andReadsTheFooterOnly() throws IOException {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(recognizer.extractMeta(anyString(), any())).thenReturn(new ActReceiptExtractor.Recognized(
                "Епіцентр", LocalDate.of(2026, 8, 18), new BigDecimal("483.50")));

        ActReceiptRecognizeResponse read = service.recognize(ACT, OWNER, jpeg());

        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo("483.50");
        assertThat(read.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 18));
    }

    @Test
    void signedActSpendsNoModelCall() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.SIGNED));

        assertThatThrownBy(() -> service.recognize(ACT, OWNER, jpeg()))
                .isInstanceOf(WorkActSignedException.class);

        verifyNoInteractions(recognizer);
    }

    // ---- the QR path (fiscal-qr iteration) --------------------------------

    @Test
    void qrPathIsFree_andPurelyLocal() {
        // No model call, and no ДПС lookup either: the lookup only ever added the seller name and
        // the positions, and the act carries no positions any more. The stub is on read(QR, false)
        // EXACTLY — a call with true would find no stub and return null (receipts-batch: this fires
        // automatically on every photo of a batch, so it must not wait on a third party).
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(fiscalQr.read(QR, false)).thenReturn(Optional.of(fiscalReceipt()));

        ActReceiptRecognizeResponse read = service.readQr(ACT, OWNER, QR);

        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo("690.00");
        assertThat(read.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void anUnreadableCodeIsSoftSoTheDialogCanFallBackToThePhoto() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(fiscalQr.read(QR, false)).thenReturn(Optional.empty());

        assertThat(service.readQr(ACT, OWNER, QR).recognized()).isFalse();
    }

    @Test
    void signedActRefusesTheQrPathToo() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.SIGNED));

        assertThatThrownBy(() -> service.readQr(ACT, OWNER, QR))
                .isInstanceOf(WorkActSignedException.class);
        verifyNoInteractions(fiscalQr);
    }

    // ---- recognition of the ALREADY-STORED photo (receipts-batch) ---------

    @Test
    void storedPhotoIsRecognizedWithoutASecondUpload() throws IOException {
        // The «✨ Розпізнати» button on a receipt card reads the photo the batch already uploaded:
        // re-sending the image is exactly what the master could not afford («з недостатньою
        // швидкістю інтернету довго думає»), and reading it server-side also survives a reload.
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(receiptRepository.findByIdAndWorkActId(RECEIPT, ACT)).thenReturn(Optional.of(storedReceipt()));
        when(storage.open("receipts/x.jpg")).thenReturn(Optional.of(new java.io.ByteArrayInputStream(jpeg().getBytes())));
        when(storage.contentType("receipts/x.jpg")).thenReturn(Optional.of("image/jpeg"));
        when(recognizer.extractMeta(anyString(), any())).thenReturn(new ActReceiptExtractor.Recognized(
                "Епіцентр", LocalDate.of(2026, 8, 18), new BigDecimal("483.50")));

        ActReceiptRecognizeResponse read = service.recognizeStored(ACT, RECEIPT, OWNER);

        assertThat(read.recognized()).isTrue();
        assertThat(read.amount()).isEqualByComparingTo("483.50");
    }

    @Test
    void aSignedActRefusesTheStoredReadBeforeTouchingStorage() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.SIGNED));

        assertThatThrownBy(() -> service.recognizeStored(ACT, RECEIPT, OWNER))
                .isInstanceOf(WorkActSignedException.class);

        verifyNoInteractions(storage);
        verifyNoInteractions(recognizer);
    }

    private static final UUID RECEIPT = UUID.randomUUID();

    private static com.majstr.backend.entity.WorkActReceipt storedReceipt() {
        return com.majstr.backend.entity.WorkActReceipt.builder()
                .id(RECEIPT).label("Чек №1").amount(BigDecimal.ZERO).storageKey("receipts/x.jpg").build();
    }

    private static final String QR = "fn=4000123456&id=17&date=20260815&time=143005&sm=690.00";

    private static com.majstr.backend.service.fiscal.FiscalReceipt fiscalReceipt() {
        return new com.majstr.backend.service.fiscal.FiscalReceipt(
                "Епіцентр", LocalDate.of(2026, 8, 15), new BigDecimal("690.00"),
                List.of(new com.majstr.backend.service.importer.EstimateExtractor.Extracted.Line(
                        "Шпаклівка", "шт", new BigDecimal("2"), new BigDecimal("345"), "MATERIAL", null)));
    }

    private static MockMultipartFile jpeg() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", bytes);
    }

    private static WorkActResponse act(WorkActStatus status) {
        return new WorkActResponse(
                ACT, UUID.randomUUID(), "7", null, WorkActKind.INTERIM, status,
                LocalDate.now(), null, null, null, null, null,
                false, false, true, true,
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, false, null,
                List.of(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null);
    }
}
