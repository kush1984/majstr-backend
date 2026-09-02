package com.majstr.backend.service.importer;

import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ReceiptItemsCommitRequest;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReceiptImportServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private UserRepository userRepository;
    @Mock private EstimateExtractor extractor;
    @Mock private EstimateService estimateService;
    @Mock private com.majstr.backend.service.fiscal.FiscalQrService fiscalQr;

    private ReceiptImportService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReceiptImportService(featureGuard, userRepository, extractor, estimateService, fiscalQr);
    }

    @Test
    void parse_isBlockedForFreePlan_neverCallsTheModel() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(user()));
        willThrow(new FeatureNotAvailableException(Feature.RECEIPT_IMPORT, Plan.FREE))
                .given(featureGuard).requireFeature(any(User.class), eq(Feature.RECEIPT_IMPORT));

        assertThatThrownBy(() -> service.parse(ownerId, estimateId, "r.jpg", "image/jpeg", new byte[]{1}))
                .isInstanceOf(FeatureNotAvailableException.class);
        verify(extractor, never()).extractReceiptFromImage(any(), any());
    }

    @Test
    void parse_rejectsSignedEstimate_neverCallsTheModel() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(user()));
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.SIGNED));

        assertThatThrownBy(() -> service.parse(ownerId, estimateId, "r.jpg", "image/jpeg", new byte[]{1}))
                .isInstanceOf(EstimateSignedException.class);
        verify(extractor, never()).extractReceiptFromImage(any(), any());
    }

    @Test
    void parse_normalizesReceiptLines() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(user()));
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(extractor.extractReceiptFromImage(eq("image/jpeg"), any())).willReturn(
                new EstimateExtractor.Extracted(List.of(
                        new EstimateExtractor.Extracted.Line(
                                "Цемент М500", "шт", new BigDecimal("2"), new BigDecimal("180"),
                                "MATERIAL", null)),
                        null));

        EstimateImportParseResponse resp = service.parse(ownerId, estimateId, "r.jpg", "image/jpeg", new byte[]{1});

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).name()).isEqualTo("Цемент М500");
        assertThat(resp.items().get(0).unit()).isEqualTo(Unit.PIECE);
        assertThat(resp.items().get(0).type()).isEqualTo(ItemType.MATERIAL);
        assertThat(resp.items().get(0).issues()).isEmpty(); // unit/qty/price all readable
        assertThat(resp.depositAmount()).isNull(); // receipts never carry a deposit
    }

    @Test
    void parse_rejectsNonImageUpload() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(user()));
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));

        assertThatThrownBy(() -> service.parse(ownerId, estimateId, "prices.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1}))
                .isInstanceOf(com.majstr.backend.exception.CatalogImportException.class);
        verify(extractor, never()).extractReceiptFromImage(any(), any());
    }

    @Test
    void commit_appendsMappedItemsToEstimate() {
        EstimateResponse updated = estimate(EstimateStatus.DRAFT);
        given(estimateService.appendItems(eq(estimateId), anyList(), eq(ownerId))).willReturn(updated);

        ReceiptItemsCommitRequest req = new ReceiptItemsCommitRequest(List.of(
                new ReceiptItemsCommitRequest.CommitItem(
                        "Цемент", Unit.PIECE, new BigDecimal("2"), new BigDecimal("180"),
                        ItemType.MATERIAL, null)));

        EstimateResponse resp = service.commit(ownerId, estimateId, req);

        assertThat(resp).isSameAs(updated);
        verify(estimateService).appendItems(eq(estimateId), anyList(), eq(ownerId));
    }

    @Test
    void commit_isNotAPaidCapability() {
        // The gate lives on READING a receipt, not on appending lines: since the QR path is free,
        // a gate here would hand a FREE master his own receipt's positions and refuse to add them.
        given(estimateService.appendItems(eq(estimateId), anyList(), eq(ownerId)))
                .willReturn(estimate(EstimateStatus.DRAFT));

        service.commit(ownerId, estimateId, new ReceiptItemsCommitRequest(List.of(
                new ReceiptItemsCommitRequest.CommitItem(
                        "Цемент", Unit.PIECE, new BigDecimal("2"), new BigDecimal("180"),
                        ItemType.MATERIAL, null))));

        verify(featureGuard, never()).requireFeature(any(User.class), any(Feature.class));
    }

    @Test
    void parseQr_isFreeAndReturnsTheSameReviewShapeAsAPhoto() {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(fiscalQr.read("fn=1&id=2&sm=690&date=20260815")).willReturn(Optional.of(
                new com.majstr.backend.service.fiscal.FiscalReceipt(
                        "Епіцентр", java.time.LocalDate.of(2026, 8, 15), new BigDecimal("690.00"),
                        List.of(new EstimateExtractor.Extracted.Line(
                                "Шпаклівка", "шт", new BigDecimal("2"), new BigDecimal("345"),
                                "MATERIAL", null)))));

        EstimateImportParseResponse resp = service.parseQr(ownerId, estimateId, "fn=1&id=2&sm=690&date=20260815");

        assertThat(resp.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Шпаклівка");
            assertThat(item.unit()).isEqualTo(Unit.PIECE);
            assertThat(item.type()).isEqualTo(ItemType.MATERIAL);
            assertThat(item.issues()).isEmpty();
        });
        verify(featureGuard, never()).requireFeature(any(User.class), any(Feature.class));
    }

    @Test
    void parseQr_failsLoudlyWhenThereAreNoPositions() {
        // This flow exists only to produce lines, so an empty review would read as «чек порожній»
        // instead of «спробуйте фото». The act's dialog is the one that answers softly.
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(fiscalQr.read("x")).willReturn(Optional.of(
                new com.majstr.backend.service.fiscal.FiscalReceipt(
                        null, java.time.LocalDate.of(2026, 8, 15), new BigDecimal("690.00"), List.of())));

        assertThatThrownBy(() -> service.parseQr(ownerId, estimateId, "x"))
                .isInstanceOf(com.majstr.backend.exception.CatalogImportException.class);
    }

    @Test
    void parseQr_rejectsAnUnreadableCodeAndASignedEstimate() {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.SIGNED));
        assertThatThrownBy(() -> service.parseQr(ownerId, estimateId, "whatever"))
                .isInstanceOf(EstimateSignedException.class);
        verify(fiscalQr, never()).read(any());
    }

    @Test
    void parseQr_unreadablePayloadIsAnError() {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(fiscalQr.read("https://example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.parseQr(ownerId, estimateId, "https://example.com"))
                .isInstanceOf(com.majstr.backend.exception.CatalogImportException.class);
    }

    // ---- helpers ----------------------------------------------------------

    private User user() {
        return User.builder().id(ownerId).plan(Plan.PRO).build();
    }

    private EstimateResponse estimate(EstimateStatus status) {
        BigDecimal zero = BigDecimal.ZERO;
        return new EstimateResponse(estimateId, projectId, "Кошторис", status,
                null, null, null, Instant.now(), Instant.now(), List.of(), zero, zero, zero, null, zero, List.of());
    }
}
