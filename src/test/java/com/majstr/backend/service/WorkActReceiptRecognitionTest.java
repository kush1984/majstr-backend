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
