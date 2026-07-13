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
    @Mock private ClaudeEstimateExtractor extractor;
    @Mock private EstimateService estimateService;

    private ReceiptImportService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReceiptImportService(featureGuard, userRepository, extractor, estimateService);
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
                new ClaudeEstimateExtractor.Extracted(List.of(
                        new ClaudeEstimateExtractor.Extracted.Line(
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
        given(userRepository.findById(ownerId)).willReturn(Optional.of(user()));
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

    // ---- helpers ----------------------------------------------------------

    private User user() {
        return User.builder().id(ownerId).plan(Plan.PRO).build();
    }

    private EstimateResponse estimate(EstimateStatus status) {
        BigDecimal zero = BigDecimal.ZERO;
        return new EstimateResponse(estimateId, projectId, "Кошторис", status,
                null, null, Instant.now(), Instant.now(), List.of(), zero, zero, zero, null, zero);
    }
}
