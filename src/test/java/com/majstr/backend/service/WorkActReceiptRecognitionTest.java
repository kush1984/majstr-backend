package com.majstr.backend.service;

import com.majstr.backend.dto.ActReceiptRecognizeResponse;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.repository.UserRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The receipt-recognition gate is per MODE, not per endpoint (master decision, 2026-08-23): the
 * meta pass — label / date / total off the footer — is FREE, the {@code withItems} item-table pass
 * stays behind {@code Feature.RECEIPT_IMPORT}. These pin that split, because the cheap half being
 * free is exactly the kind of thing a later "tidy the guard to the top of the method" refactor
 * would silently undo.
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
    @Mock private FeatureGuard featureGuard;
    @Mock private UserRepository userRepository;
    @Mock private ProjectPhotoService photoService;
    @Mock private com.majstr.backend.service.fiscal.FiscalQrService fiscalQr;

    @InjectMocks private WorkActReceiptService service;

    @Test
    void metaPassIsFree() throws IOException {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(recognizer.extractMeta(anyString(), any())).thenReturn(new ActReceiptExtractor.Recognized(
                "Епіцентр", LocalDate.of(2026, 8, 18), new BigDecimal("483.50"), List.of()));

        ActReceiptRecognizeResponse read = service.recognize(ACT, OWNER, jpeg(), false);

        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo("483.50");
        // The FREE half must not even look the user up, let alone gate them.
        verifyNoInteractions(featureGuard);
        verify(recognizer, never()).extractWithItems(anyString(), any());
    }

    @Test
    void itemPassStaysPro() {
        User owner = new User();
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(owner));
        doThrow(new FeatureNotAvailableException(Feature.RECEIPT_IMPORT, Plan.FREE))
                .when(featureGuard).requireFeature(owner, Feature.RECEIPT_IMPORT);

        assertThatThrownBy(() -> service.recognize(ACT, OWNER, jpeg(), true))
                .isInstanceOf(FeatureNotAvailableException.class);

        // A blocked plan must never spend the (expensive) model call.
        verify(recognizer, never()).extractWithItems(anyString(), any());
        verify(recognizer, never()).extractMeta(anyString(), any());
    }

    @Test
    void signedActSpendsNoModelCallInEitherMode() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.SIGNED));

        assertThatThrownBy(() -> service.recognize(ACT, OWNER, jpeg(), false))
                .isInstanceOf(WorkActSignedException.class);
        assertThatThrownBy(() -> service.recognize(ACT, OWNER, jpeg(), true))
                .isInstanceOf(WorkActSignedException.class);

        verifyNoInteractions(recognizer);
    }

    // ---- the QR path (fiscal-qr iteration) --------------------------------

    @Test
    void qrPathIsFreeInBothModes() {
        // The positions cost no model call here, so nothing about them is a paid capability —
        // «безкоштовно все, що дав QR» (master decision, 2026-08-23).
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(fiscalQr.read(QR)).thenReturn(Optional.of(fiscalReceipt()));

        ActReceiptRecognizeResponse read = service.readQr(ACT, OWNER, QR, true);

        assertThat(read.recognized()).isTrue();
        assertThat(read.label()).isEqualTo("Епіцентр");
        assertThat(read.amount()).isEqualByComparingTo("690.00");
        assertThat(read.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(read.items()).singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("Шпаклівка"));
        verifyNoInteractions(featureGuard);
    }

    @Test
    void qrWithoutTheTickCarriesNoPositions() {
        // withItems is the master's «перенести позиції» tick, not a plan check — a receipt whose
        // positions were not asked for must not quietly acquire them.
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(fiscalQr.read(QR)).thenReturn(Optional.of(fiscalReceipt()));

        ActReceiptRecognizeResponse read = service.readQr(ACT, OWNER, QR, false);

        assertThat(read.recognized()).isTrue();
        assertThat(read.amount()).isEqualByComparingTo("690.00"); // the money still prefills
        assertThat(read.items()).isEmpty();
    }

    @Test
    void anUnreadableCodeIsSoftSoTheDialogCanFallBackToThePhoto() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.DRAFT));
        when(fiscalQr.read(QR)).thenReturn(Optional.empty());

        assertThat(service.readQr(ACT, OWNER, QR, true).recognized()).isFalse();
    }

    @Test
    void signedActRefusesTheQrPathToo() {
        when(actService.get(ACT, OWNER)).thenReturn(act(WorkActStatus.SIGNED));

        assertThatThrownBy(() -> service.readQr(ACT, OWNER, QR, false))
                .isInstanceOf(WorkActSignedException.class);
        verifyNoInteractions(fiscalQr);
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
