package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportCommitRequest;
import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.dto.EstimateImportCommitRequest;
import com.majstr.backend.dto.EstimateImportCommitResponse;
import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ProjectPaymentStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData;
import com.majstr.backend.service.PaymentService;
import com.majstr.backend.service.importer.EstimateExtractor.Extracted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EstimateImportServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private UserRepository userRepository;
    @Mock private EstimateExtractor extractor;
    @Mock private EstimateService estimateService;
    @Mock private CatalogImportService catalogImportService;
    @Mock private PaymentService paymentService;

    @InjectMocks private EstimateImportService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    private void givenUser(Plan plan) {
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(plan).build()));
    }

    @Test
    void parse_normalizesUnitsAndFlagsUnreadableValues() {
        givenUser(Plan.PRO);
        given(extractor.extractFromText(anyString())).willReturn(new Extracted(List.of(
                new Extracted.Line("Малярка", "м²", new BigDecimal("5"), new BigDecimal("100"), "WORK", "Кухня"),
                new Extracted.Line("Демонтаж", null, new BigDecimal("0"), new BigDecimal("0"), "матеріал", null)
        ), new BigDecimal("500")));

        EstimateImportParseResponse resp = service.parse(ownerId, "kosht.csv", "text/csv", "x".getBytes());

        verify(featureGuard).requireFeature(any(User.class), eq(Feature.ESTIMATE_IMPORT));
        verify(extractor, never()).extractFromImage(anyString(), any());
        assertThat(resp.items()).hasSize(2);

        var good = resp.items().get(0);
        assertThat(good.unit()).isEqualTo(Unit.M2);
        assertThat(good.type()).isEqualTo(ItemType.WORK);
        assertThat(good.category()).isEqualTo("Кухня");
        assertThat(good.issues()).isEmpty();

        var bad = resp.items().get(1);
        assertThat(bad.unit()).isNull();
        assertThat(bad.type()).isEqualTo(ItemType.MATERIAL); // "матеріал" → MATERIAL
        assertThat(bad.issues()).containsExactlyInAnyOrder("unit", "quantity", "price");

        // The review screen still shows the detected deposit — only WHERE it lands on
        // commit changed (a project_payment now, not the estimate).
        assertThat(resp.depositAmount()).isEqualByComparingTo("500");
    }

    @Test
    void parse_usesImageBranchForAPhoto() {
        givenUser(Plan.PRO);
        byte[] bytes = "img".getBytes();
        given(extractor.extractFromImage("image/jpeg", bytes))
                .willReturn(new Extracted(List.of(), null));

        service.parse(ownerId, "photo.jpg", "image/jpeg", bytes);

        verify(extractor).extractFromImage("image/jpeg", bytes);
        verify(extractor, never()).extractFromText(anyString());
    }

    @Test
    void parse_rejectsUnsupportedFile() {
        givenUser(Plan.PRO);

        assertThatThrownBy(() -> service.parse(ownerId, "note.txt", "text/plain", "x".getBytes()))
                .isInstanceOf(CatalogImportException.class);

        verify(extractor, never()).extractFromText(anyString());
        verify(extractor, never()).extractFromImage(anyString(), any());
    }

    @Test
    void parse_requiresTheImportFeature() {
        givenUser(Plan.FREE);
        willThrow(new FeatureNotAvailableException(Feature.ESTIMATE_IMPORT, Plan.FREE))
                .given(featureGuard).requireFeature(any(User.class), eq(Feature.ESTIMATE_IMPORT));

        assertThatThrownBy(() -> service.parse(ownerId, "kosht.csv", "text/csv", "x".getBytes()))
                .isInstanceOf(FeatureNotAvailableException.class);

        verifyNoInteractions(extractor);
    }

    @Test
    void commit_createsEstimateAndUpsertsOnlyTickedCatalogItems() {
        givenUser(Plan.PRO);
        given(estimateService.createFromImport(eq(projectId), any(ImportEstimateData.class), eq(ownerId)))
                .willReturn(estimateResponse(new BigDecimal("550.00")));
        given(catalogImportService.commit(eq(ownerId), any(CatalogImportCommitRequest.class)))
                .willReturn(new CatalogImportCommitResponse(0, 1, 0));
        UUID planId = UUID.randomUUID();
        given(paymentService.add(eq(projectId), eq(ownerId), any(ProjectPaymentRequest.class), isNull()))
                .willReturn(new ProjectPaymentResponse(planId, new BigDecimal("100"), null, null, "Завдаток",
                        BigDecimal.ZERO, new BigDecimal("100"), ProjectPaymentStatus.PLANNED, 0, List.of()));

        var req = new EstimateImportCommitRequest(projectId, "Import", new BigDecimal("100"), List.of(
                new EstimateImportCommitRequest.CommitItem("Малярка", Unit.M2, new BigDecimal("5"),
                        new BigDecimal("100"), ItemType.WORK, "Кухня", true, DedupPolicy.UPDATE_PRICE),
                new EstimateImportCommitRequest.CommitItem("Демонтаж", Unit.M2, new BigDecimal("2"),
                        new BigDecimal("50"), ItemType.WORK, null, false, null)));

        EstimateImportCommitResponse resp = service.commit(ownerId, req);

        // Estimate gets both items.
        ArgumentCaptor<ImportEstimateData> data = ArgumentCaptor.forClass(ImportEstimateData.class);
        verify(estimateService).createFromImport(eq(projectId), data.capture(), eq(ownerId));
        assertThat(data.getValue().items()).hasSize(2);
        assertThat(data.getValue().name()).isEqualTo("Import");

        // The detected deposit becomes an object-level payment: a plan row, then a receipt that
        // closes it immediately (already received) — money is payment_receipt now (V100).
        ArgumentCaptor<ProjectPaymentRequest> payment = ArgumentCaptor.forClass(ProjectPaymentRequest.class);
        verify(paymentService).add(eq(projectId), eq(ownerId), payment.capture(), isNull());
        assertThat(payment.getValue().amount()).isEqualByComparingTo("100");
        assertThat(payment.getValue().purpose()).isEqualTo("Завдаток");

        ArgumentCaptor<PaymentReceiptRequest> receipt = ArgumentCaptor.forClass(PaymentReceiptRequest.class);
        verify(paymentService).addReceipt(eq(projectId), eq(ownerId), receipt.capture(), isNull());
        assertThat(receipt.getValue().planPaymentId()).isEqualTo(planId);
        assertThat(receipt.getValue().amount()).isEqualByComparingTo("100");

        // Catalog gets ONLY the ticked item, with its per-item policy.
        ArgumentCaptor<CatalogImportCommitRequest> cat = ArgumentCaptor.forClass(CatalogImportCommitRequest.class);
        verify(catalogImportService).commit(eq(ownerId), cat.capture());
        assertThat(cat.getValue().items()).hasSize(1);
        assertThat(cat.getValue().items().get(0).name()).isEqualTo("Малярка");
        assertThat(cat.getValue().items().get(0).policy()).isEqualTo(DedupPolicy.UPDATE_PRICE);
        assertThat(cat.getValue().defaultPolicy()).isEqualTo(DedupPolicy.SKIP);

        assertThat(resp.estimateId()).isEqualTo(estimateId);
        assertThat(resp.total()).isEqualByComparingTo("550.00");
        assertThat(resp.catalogUpdated()).isEqualTo(1);
    }

    @Test
    void commit_skipsCatalogWhenNothingTicked() {
        givenUser(Plan.PRO);
        given(estimateService.createFromImport(eq(projectId), any(ImportEstimateData.class), eq(ownerId)))
                .willReturn(estimateResponse(new BigDecimal("50.00")));

        var req = new EstimateImportCommitRequest(projectId, null, null, List.of(
                new EstimateImportCommitRequest.CommitItem("Демонтаж", Unit.M2, new BigDecimal("1"),
                        new BigDecimal("50"), ItemType.WORK, null, false, null)));

        EstimateImportCommitResponse resp = service.commit(ownerId, req);

        verify(catalogImportService, never()).commit(any(), any());
        verify(paymentService, never()).add(any(), any(), any(), any()); // no deposit in this request
        assertThat(resp.catalogCreated()).isZero();
        assertThat(resp.catalogUpdated()).isZero();
        assertThat(resp.catalogSkipped()).isZero();
    }

    private EstimateResponse estimateResponse(BigDecimal total) {
        return new EstimateResponse(estimateId, projectId, "Import", EstimateStatus.DRAFT, null, null,
                Instant.now(), Instant.now(), List.of(),
                new BigDecimal("0.00"), new BigDecimal("0.00"), total, null, total, List.of());
    }
}
