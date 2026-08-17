package com.majstr.backend.service;

import com.majstr.backend.dto.AddCatalogItemsBatchRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemsOrderRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.service.measurement.MeasurementService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EstimateServiceTest {

    @Mock private EstimateRepository estimateRepository;
    @Mock private EstimateItemRepository itemRepository;
    @Mock private ProjectService projectService;
    @Mock private ProjectRepository projectRepository;
    @Mock private CatalogService catalogService;
    @Mock private LimitService limitService;
    @Mock private MeasurementService measurementService;
    @Mock private EstimatePdfService pdfService;
    @Mock private ProjectPhotoRepository photoRepository;
    @Mock private StorageService storage;
    @Mock private WorkActItemRepository workActItemRepository;

    @InjectMocks private EstimateService estimateService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    @Test
    void createForProject_persistsEstimateAndReturnsEmptyTotals() {
        Project project = ownedProject(ownerId);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project);
        given(estimateRepository.save(any(Estimate.class))).willAnswer(invocation -> {
            Estimate e = invocation.getArgument(0);
            e.setId(estimateId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        EstimateResponse response = estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, "kickoff", null), ownerId);

        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.status()).isEqualTo(EstimateStatus.DRAFT);
        assertThat(response.items()).isEmpty();
        assertThat(response.worksSubtotal()).isEqualByComparingTo("0.00");
        assertThat(response.materialsSubtotal()).isEqualByComparingTo("0.00");
        assertThat(response.total()).isEqualByComparingTo("0.00");
        verify(estimateRepository).save(any(Estimate.class));
        verify(projectRepository).incrementEstimatesCreated(projectId); // lifetime churn counter
    }

    @Test
    void createForProject_blockedWhenEstimateLimitReached_doesNotSave() {
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        willThrow(new LimitExceededException(Limit.MAX_ESTIMATES_PER_PROJECT, 3, Plan.FREE))
                .given(limitService).requireCanAddEstimate(ownerId, projectId);

        assertThatThrownBy(() -> estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, "x", null), ownerId))
                .isInstanceOf(LimitExceededException.class);

        verify(estimateRepository, never()).save(any(Estimate.class));
    }

    @Test
    void get_computesWorksAndMaterialsSubtotalsWithHalfUpRounding() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        // 12.345 * 100.55 = 1241.290... → 1241.29 (HALF_UP)
        EstimateItem work = item(ItemType.WORK, "Tiling", "12.345", "100.55");
        // 3.5 * 49.99 = 174.965 → 174.97 (HALF_UP, .5 rounds up)
        EstimateItem material = item(ItemType.MATERIAL, "Grout", "3.5", "49.99");
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(List.of(work, material));

        EstimateResponse resp = estimateService.get(estimateId, ownerId);

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).lineTotal()).isEqualByComparingTo("1241.29");
        assertThat(resp.items().get(1).lineTotal()).isEqualByComparingTo("174.97");
        assertThat(resp.worksSubtotal()).isEqualByComparingTo("1241.29");
        assertThat(resp.materialsSubtotal()).isEqualByComparingTo("174.97");
        assertThat(resp.total()).isEqualByComparingTo("1416.26");
    }

    @Test
    void renderPdf_unverifiedOwner_throwsEmailNotVerified() {
        // Anti-abuse: the client-facing PDF requires a verified email even on FREE.
        Estimate estimate = ownedEstimate(ownerId); // owner emailVerified defaults false
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));

        assertThatThrownBy(() -> estimateService.renderPdf(estimateId, ownerId))
                .isInstanceOf(com.majstr.backend.exception.EmailNotVerifiedException.class);
    }

    @Test
    void renderPdf_embedsOnlyPhotosOfThisEstimatesProject() throws Exception {
        Estimate estimate = ownedEstimate(ownerId);
        estimate.getProject().getOwner().setEmailVerified(true);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        UUID ownId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        ProjectPhoto own = ProjectPhoto.builder()
                .id(ownId).projectId(projectId).storageKey("photos/a.jpg")
                .source(PhotoSource.RECEIPT).visibility(PhotoVisibility.PRIVATE).estimateId(estimateId)
                .build();
        // Only a photo of THIS estimate's project resolves; a foreign id returns empty.
        given(photoRepository.findByIdAndProjectId(ownId, projectId)).willReturn(Optional.of(own));
        given(photoRepository.findByIdAndProjectId(foreignId, projectId)).willReturn(Optional.empty());
        given(storage.open("photos/a.jpg"))
                .willReturn(Optional.of(new ByteArrayInputStream(new byte[]{9, 9, 9})));

        ArgumentCaptor<EstimatePdfService.PdfModel> captor =
                ArgumentCaptor.forClass(EstimatePdfService.PdfModel.class);
        given(pdfService.render(captor.capture())).willReturn(new byte[]{1});

        // Ask for a FOREIGN id AND an owned one — only the owned photo is embedded, so a crafted
        // request can never pull another owner's photo into the PDF.
        estimateService.renderPdf(estimateId, ownerId, List.of(foreignId, ownId));

        List<byte[]> embedded = captor.getValue().receiptImages();
        assertThat(embedded).hasSize(1);
        assertThat(embedded.get(0)).containsExactly(9, 9, 9);
    }

    @Test
    void get_throwsAccessDeniedWhenEstimateBelongsToAnotherUser() {
        Estimate estimate = ownedEstimate(otherUserId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));

        assertThatThrownBy(() -> estimateService.get(estimateId, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addItem_attachesItemToOwnedEstimate() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        given(itemRepository.save(any(EstimateItem.class))).willAnswer(inv -> {
            EstimateItem i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "Plastering", "  Walls  ", Unit.M2,
                new BigDecimal("25.000"), new BigDecimal("180.00"), 1, null, false, null, null);

        var resp = estimateService.addItem(estimateId, req, ownerId);

        assertThat(resp.lineTotal()).isEqualByComparingTo("4500.00");
        assertThat(resp.type()).isEqualTo(ItemType.WORK);
        // Category is normalized (trimmed) on write.
        assertThat(resp.category()).isEqualTo("Walls");
    }

    @Test
    void addItem_withMeasurementRefs_recomputesQuantityServerSide() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        given(itemRepository.save(any(EstimateItem.class))).willAnswer(inv -> inv.getArgument(0));
        // Server is the source of truth — the sum, not the client's number.
        given(measurementService.sumForRefs(eq(projectId), anyList(), eq(Unit.M2)))
                .willReturn(new BigDecimal("30.000"));

        List<UUID> refs = List.of(UUID.randomUUID(), UUID.randomUUID());
        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "Стеля", null, Unit.M2,
                new BigDecimal("999.000"), // client preview — must be ignored
                new BigDecimal("100.00"), 0, refs, false, null, null);

        var resp = estimateService.addItem(estimateId, req, ownerId);

        assertThat(resp.quantity()).isEqualByComparingTo("30.000"); // recomputed, not 999
        assertThat(resp.lineTotal()).isEqualByComparingTo("3000.00"); // 30 × 100
        assertThat(resp.measurementRefs()).hasSize(2);
        assertThat(resp.quantityManual()).isFalse();
    }

    @Test
    void addItem_manualQuantityKeepsRefsButIsNotRecomputed() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        given(itemRepository.save(any(EstimateItem.class))).willAnswer(inv -> inv.getArgument(0));

        List<UUID> refs = List.of(UUID.randomUUID());
        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "Стеля", null, Unit.M2,
                new BigDecimal("42.000"), new BigDecimal("100.00"), 0, refs, true, null, null); // manual

        var resp = estimateService.addItem(estimateId, req, ownerId);

        assertThat(resp.quantity()).isEqualByComparingTo("42.000"); // kept, not recomputed
        assertThat(resp.quantityManual()).isTrue();
        assertThat(resp.measurementRefs()).hasSize(1); // selection memory kept
        verify(measurementService, never()).sumForRefs(any(), anyList(), any());
    }

    @Test
    void addItem_rejectsWhenEstimateBelongsToAnotherUser() {
        Estimate estimate = ownedEstimate(otherUserId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false, null, null);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- signed estimates are immutable ------------------------------------

    @Test
    void update_rejectsWhenEstimateIsSigned() {
        Estimate signed = signedEstimate();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.DRAFT, null, null, null), ownerId))
                .isInstanceOf(EstimateSignedException.class);
        assertThat(signed.getStatus()).isEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void update_rejectsManualTransitionToSigned() {
        Estimate draft = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.SIGNED, null, null, null), ownerId))
                .isInstanceOf(InvalidEstimateStatusException.class);
        assertThat(draft.getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    @Test
    void update_allowsTransitionsBetweenUnsignedStatuses() {
        Estimate sent = ownedEstimate(ownerId);
        sent.setStatus(EstimateStatus.SENT);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(sent));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        EstimateResponse resp = estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.REJECTED, null, null, null), ownerId);

        assertThat(resp.status()).isEqualTo(EstimateStatus.REJECTED);
    }

    @Test
    void update_toRejected_stopsCountingItAsIncome() {
        // A rejected estimate is a deal the client turned down. The income queries exclude
        // REJECTED, so leaving the flag set would show a ticked "count in economy" box on an
        // estimate that is not counted — the flag and the number must agree.
        Estimate sent = ownedEstimate(ownerId);
        sent.setStatus(EstimateStatus.SENT);
        sent.setCountInEconomy(true);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(sent));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.REJECTED, null, null, null), ownerId);

        assertThat(sent.isCountInEconomy()).isFalse();
    }

    // Deposit editing via this endpoint was removed (payments-economy-portal iteration) — money
    // moved to project_payment, object-level. See PaymentServiceTest for the new coverage.

    @Test
    void addItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false, null, null);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    @Test
    void updateItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false, null, null);

        assertThatThrownBy(() -> estimateService.updateItem(estimateId, UUID.randomUUID(), req, ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    @Test
    void deleteItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        assertThatThrownBy(() -> estimateService.deleteItem(estimateId, UUID.randomUUID(), ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    // ---- delete (signed is protected) --------------------------------------

    @Test
    void delete_removesDraftEstimate() {
        Estimate draft = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        estimateService.delete(estimateId, ownerId);

        verify(estimateRepository).delete(draft);
        verify(projectRepository).incrementEstimatesDeleted(projectId); // lifetime churn counter
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        // Idempotent on purpose. The offline outbox replays a delete whose RESPONSE was lost —
        // the row is already gone, the master's phone never heard so. A 404 there is classified
        // as a permanent rejection, and they would be shown "not saved to cloud" for something
        // that saved perfectly.
        given(estimateRepository.findById(estimateId)).willReturn(Optional.empty());

        assertThatCode(() -> estimateService.delete(estimateId, ownerId)).doesNotThrowAnyException();

        verify(estimateRepository, never()).delete(any(Estimate.class));
        verify(projectRepository, never()).incrementEstimatesDeleted(any()); // no phantom churn
    }

    @Test
    void delete_rejectsSignedEstimate_doesNotDelete() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        assertThatThrownBy(() -> estimateService.delete(estimateId, ownerId))
                .isInstanceOf(EstimateSignedException.class);

        verify(estimateRepository, never()).delete(any(Estimate.class));
    }

    // ---- reopen (owner only, signed → draft) -------------------------------

    @Test
    void reopen_returnsSignedEstimateToDraftAndClearsSignatureWithAudit() {
        Estimate signed = signedEstimate();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        EstimateResponse resp = estimateService.reopen(estimateId, ownerId);

        assertThat(resp.status()).isEqualTo(EstimateStatus.DRAFT);
        assertThat(signed.getStatus()).isEqualTo(EstimateStatus.DRAFT);
        assertThat(signed.getSignedAt()).isNull();
        assertThat(signed.getSignerName()).isNull();
        assertThat(signed.getSignerPhone()).isNull();
        assertThat(signed.getReopenedBy()).isEqualTo(ownerId);
        assertThat(signed.getReopenedAt()).isNotNull();
    }

    @Test
    void reopen_rejectsWhenEstimateNotSigned() {
        Estimate draft = ownedEstimate(ownerId); // DRAFT
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> estimateService.reopen(estimateId, ownerId))
                .isInstanceOf(InvalidEstimateStatusException.class);
        assertThat(draft.getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    @Test
    void reopen_rejectsWhenEstimateBelongsToAnotherUser() {
        Estimate signed = ownedEstimate(otherUserId);
        signed.setStatus(EstimateStatus.SIGNED);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));

        assertThatThrownBy(() -> estimateService.reopen(estimateId, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- supersede banner (economy-rework) ---------------------------------

    @Test
    void update_clearsAStaleSupersededBanner_whenTheMasterEditsIt() {
        // requireNotSigned runs on every write path — editing IS "the master has seen and acted
        // on it", so the banner (from an auto-reopen when a discounted duplicate got signed) goes
        // away without a dedicated "acknowledge" click.
        Estimate draft = ownedEstimate(ownerId);
        draft.setSupersededByEstimateId(UUID.randomUUID());
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.SENT, null, null, null), ownerId);

        assertThat(draft.getSupersededByEstimateId()).isNull();
    }

    @Test
    void dismissSupersededNotice_clearsTheFlag_touchesNothingElse() {
        Estimate draft = ownedEstimate(ownerId);
        UUID supersededBy = UUID.randomUUID();
        draft.setSupersededByEstimateId(supersededBy);
        draft.setName("Кошторис А");
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.dismissSupersededNotice(estimateId, ownerId);

        assertThat(draft.getSupersededByEstimateId()).isNull();
        assertThat(draft.getName()).isEqualTo("Кошторис А"); // untouched
        assertThat(draft.getStatus()).isEqualTo(EstimateStatus.DRAFT); // untouched
    }

    @Test
    void dismissSupersededNotice_rejectsWhenEstimateBelongsToAnotherUser() {
        Estimate draft = ownedEstimate(otherUserId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> estimateService.dismissSupersededNotice(estimateId, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void applyReopen_withNoActingOwner_leavesReopenedByNull() {
        // The system-triggered path (PublicEstimateService.doSign auto-reopening a superseded
        // parent) calls this directly with no owner — must not attribute the audit stamp to
        // anyone who didn't click anything.
        Estimate signed = signedEstimate();

        estimateService.applyReopen(signed, null);

        assertThat(signed.getStatus()).isEqualTo(EstimateStatus.DRAFT);
        assertThat(signed.getSignedAt()).isNull();
        assertThat(signed.getReopenedBy()).isNull();
        assertThat(signed.getReopenedAt()).isNotNull();
    }

    // ---- name --------------------------------------------------------------

    @Test
    void createForProject_storesTrimmedName() {
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(estimateId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });

        EstimateResponse resp = estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, null, "  Преміум  "), ownerId);

        assertThat(resp.name()).isEqualTo("Преміум");
    }

    // ---- batch add from catalog --------------------------------------------

    @Test
    void addItemsFromCatalogBatch_copiesEachCatalogItemInOneSaveAll() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        given(catalogService.loadOwned(c1, ownerId))
                .willReturn(catalogItem("Розетка", ItemType.WORK, Unit.PIECE, "180.00", "Електрика"));
        given(catalogService.loadOwned(c2, ownerId))
                .willReturn(catalogItem("Кабель", ItemType.MATERIAL, Unit.M, "38.50", "Кабель"));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.addItemsFromCatalogBatch(estimateId, List.of(
                new AddCatalogItemsBatchRequest.Entry(c1, new BigDecimal("3"), 0),
                new AddCatalogItemsBatchRequest.Entry(c2, new BigDecimal("10"), 1)), ownerId);

        ArgumentCaptor<List<EstimateItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<EstimateItem> saved = captor.getValue();
        assertThat(saved).extracting(EstimateItem::getName).containsExactly("Розетка", "Кабель");
        // price/unit/type/category copied from each catalog item; quantity from the entry
        assertThat(saved.get(0).getUnitPrice()).isEqualByComparingTo("180.00");
        assertThat(saved.get(0).getQuantity()).isEqualByComparingTo("3");
        assertThat(saved.get(0).getCategory()).isEqualTo("Електрика");
        assertThat(saved.get(1).getUnit()).isEqualTo(Unit.M);
    }

    @Test
    void addItemsFromCatalogBatch_rejectsSignedEstimate() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        assertThatThrownBy(() -> estimateService.addItemsFromCatalogBatch(estimateId, List.of(
                new AddCatalogItemsBatchRequest.Entry(UUID.randomUUID(), new BigDecimal("1"), 0)), ownerId))
                .isInstanceOf(EstimateSignedException.class);
        verify(itemRepository, never()).saveAll(anyList());
    }

    @Test
    void addItemFromCatalog_replayedWithTheSameClientId_returnsTheExistingLine() {
        // Offline authoring: picking from the catalog is how estimates are built, so it must
        // survive a replay. Without the id check a lost response would duplicate the line.
        UUID clientId = UUID.randomUUID();
        EstimateItem already = EstimateItem.builder()
                .id(clientId).estimate(ownedEstimate(ownerId)).type(ItemType.WORK)
                .name("Розетка").unit(Unit.PIECE).quantity(new BigDecimal("3"))
                .unitPrice(new BigDecimal("180.00")).sortOrder(0).build();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        given(itemRepository.findById(clientId)).willReturn(Optional.of(already));

        var resp = estimateService.addItemFromCatalog(
                estimateId, UUID.randomUUID(), new EstimateItemFromCatalogRequest(new BigDecimal("3"), 0),
                ownerId, clientId);

        assertThat(resp.id()).isEqualTo(clientId);
        verify(itemRepository, never()).save(any(EstimateItem.class)); // no duplicate
    }

    @Test
    @SuppressWarnings("unchecked")
    void addItemsFromCatalogBatch_partiallyAppliedReplay_addsOnlyTheMissingLines() {
        // A batch whose response was lost may be replayed after some lines already landed.
        // Per-entry client ids let it resume instead of duplicating the whole selection.
        UUID landed = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        given(itemRepository.findById(landed)).willReturn(Optional.of(EstimateItem.builder()
                .id(landed).estimate(ownedEstimate(ownerId)).build()));
        given(itemRepository.findById(missing)).willReturn(Optional.empty());
        given(catalogService.loadOwned(c2, ownerId))
                .willReturn(catalogItem("Кабель", ItemType.MATERIAL, Unit.M, "38.50", null));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.addItemsFromCatalogBatch(estimateId, List.of(
                new AddCatalogItemsBatchRequest.Entry(UUID.randomUUID(), new BigDecimal("3"), 0, landed),
                new AddCatalogItemsBatchRequest.Entry(c2, new BigDecimal("10"), 1, missing)), ownerId);

        ArgumentCaptor<List<EstimateItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(EstimateItem::getName).containsExactly("Кабель");
        assertThat(captor.getValue().get(0).getId()).isEqualTo(missing); // keeps the client id
    }

    // ---- consolidate -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_copiesItemsFromPickedEstimatesIntoOneNewDraft() {
        UUID srcA = UUID.randomUUID();
        UUID srcB = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(srcA)).willReturn(Optional.of(sourceEstimate(srcA, ownerId)));
        given(estimateRepository.findById(srcB)).willReturn(Optional.of(sourceEstimate(srcB, ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcA))
                .willReturn(List.of(item(ItemType.WORK, "Малярка", "2", "100")));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcB))
                .willReturn(List.of(item(ItemType.MATERIAL, "Фарба", "3", "50")));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId))
                .willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(
                projectId, "  Зведений  ", List.of(srcA, srcB), ownerId);

        assertThat(resp.name()).isEqualTo("Зведений"); // trimmed
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.worksSubtotal()).isEqualByComparingTo("200.00");
        assertThat(resp.materialsSubtotal()).isEqualByComparingTo("150.00");
        assertThat(resp.total()).isEqualByComparingTo("350.00");
        // sortOrder renumbered continuously across the two source estimates
        assertThat(saved[0]).extracting(EstimateItem::getSortOrder).containsExactly(0, 1);
        verify(limitService).requireCanAddEstimate(ownerId, projectId); // FREE cap enforced
        verify(projectRepository).incrementEstimatesCreated(projectId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_theSameSourceListedTwiceIsCopiedOnce() {
        // A double tap in the picker (or a retried request) used to copy that estimate's
        // items TWICE, silently doubling the rollup the master then shows a client. This is
        // NOT item-level dedup — equal positions from DIFFERENT estimates still concat.
        UUID srcA = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(srcA)).willReturn(Optional.of(sourceEstimate(srcA, ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcA))
                .willReturn(List.of(item(ItemType.WORK, "Малярка", "2", "100")));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId))
                .willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(
                projectId, "Зведений", List.of(srcA, srcA), ownerId);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.total()).isEqualByComparingTo("200.00"); // not 400 — copied once
    }

    @Test
    void consolidate_defaultsNameWhenBlank() {
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(src)).willReturn(Optional.of(sourceEstimate(src, ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src)).willReturn(List.of());
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willReturn(List.of());

        EstimateResponse resp = estimateService.consolidate(projectId, "  ", List.of(src), ownerId);

        assertThat(resp.name()).isEqualTo("Зведений кошторис");
    }

    @Test
    void consolidate_rejectsSourceFromAnotherProject() {
        UUID src = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        // Same owner, but the source estimate belongs to a DIFFERENT project.
        User owner = User.builder().id(ownerId).build();
        Project otherProject = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        Estimate foreign = Estimate.builder().id(src).project(otherProject)
                .status(EstimateStatus.DRAFT).build();
        given(estimateRepository.findById(src)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> estimateService.consolidate(projectId, null, List.of(src), ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_excludesRollupAndKeepsSourcesCounting() {
        UUID srcA = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        Estimate[] savedConsolidated = new Estimate[1];
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            savedConsolidated[0] = e;
            return e;
        });
        Estimate source = sourceEstimate(srcA, ownerId); // counts by default
        given(estimateRepository.findById(srcA)).willReturn(Optional.of(source));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcA)).willReturn(List.of());
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willReturn(List.of());

        estimateService.consolidate(projectId, "Зведений", List.of(srcA), ownerId);

        // New rule: everything counts by default; the rollup is the one exclusion,
        // so its sources keep counting (no double-count).
        assertThat(savedConsolidated[0].isCountInEconomy()).isFalse(); // rollup excluded
        assertThat(source.isCountInEconomy()).isTrue();                // sources still count
    }

    @Test
    void consolidate_recordsSourceLineageSoTheRollupCanOfferTheirReceipts() {
        UUID srcA = UUID.randomUUID();
        UUID srcB = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(srcA)).willReturn(Optional.of(sourceEstimate(srcA, ownerId)));
        given(estimateRepository.findById(srcB)).willReturn(Optional.of(sourceEstimate(srcB, ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcA)).willReturn(List.of());
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(srcB)).willReturn(List.of());
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willReturn(List.of());

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(srcA, srcB), ownerId);

        // The rollup remembers its sources, so its PDF can later offer their receipts (which stay
        // on the sources — the line items are copied by value, the receipts are not).
        assertThat(resp.sourceEstimateIds()).containsExactlyInAnyOrder(srcA, srcB);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_freezesAPercentLineAtItsSourceAmountInsteadOfZeroing() {
        // Regression: a naive copy dropped percent_base_kind, so a «−10 % від кошторису» discount
        // landed as MANUAL-of-0 and recomputed to 0,00 ₴ — the discount silently vanished and the
        // rollup total no longer matched the sum of its sources.
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(src)).willReturn(Optional.of(sourceEstimate(src, ownerId)));
        EstimateItem work = item(ItemType.WORK, "Мурування", "1", "1000"); // 1000,00
        EstimateItem discount = EstimateItem.builder()
                .id(UUID.randomUUID()).type(ItemType.WORK).name("Знижка")
                .unit(Unit.PERCENT).quantity(new BigDecimal("-10.000")).unitPrice(BigDecimal.ZERO)
                .percentBaseKind(PercentBaseKind.TOTAL).lineTotal(new BigDecimal("-100.00")).sortOrder(1)
                .build();
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src)).willReturn(List.of(work, discount));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(src), ownerId);

        // The discount survived the merge: 1000 − 100 = 900, not 1000.
        assertThat(resp.total()).isEqualByComparingTo("900.00");
        EstimateItemResponse frozen = resp.items().stream()
                .filter(i -> i.unit() == Unit.PERCENT).findFirst().orElseThrow();
        assertThat(frozen.lineTotal()).isEqualByComparingTo("-100.00"); // frozen at the source amount
        // Reconstructed base for display: −100 × 100 / −10 = 1000 → reads «−10 % від 1000,00 ₴».
        assertThat(frozen.unitPrice()).isEqualByComparingTo("1000.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_ordinaryLineGetsNoOriginLabel() {
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        given(estimateRepository.findById(src)).willReturn(Optional.of(sourceEstimate(src, ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src))
                .willReturn(List.of(item(ItemType.WORK, "Малярка", "2", "100")));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(src), ownerId);

        assertThat(resp.items().get(0).baseOriginLabel()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_percentOfTotal_getsAProvenanceLabelNamingTheSourceEstimateAndScope() {
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        Estimate source = sourceEstimate(src, ownerId);
        source.setName("Квартира — чорнові");
        given(estimateRepository.findById(src)).willReturn(Optional.of(source));
        EstimateItem discount = EstimateItem.builder()
                .id(UUID.randomUUID()).type(ItemType.WORK).name("Знижка")
                .unit(Unit.PERCENT).quantity(new BigDecimal("-15.000")).unitPrice(BigDecimal.ZERO)
                .percentBaseKind(PercentBaseKind.TOTAL).lineTotal(new BigDecimal("-150.00")).sortOrder(0)
                .estimate(source)
                .build();
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src)).willReturn(List.of(discount));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(src), ownerId);

        assertThat(resp.items().get(0).baseOriginLabel())
                .isEqualTo("-15% від робіт · кошторис «Квартира — чорнові»");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_percentOfPosition_namesTheBasePositionInTheLabel() {
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        Estimate source = sourceEstimate(src, ownerId);
        source.setName("Санвузол");
        given(estimateRepository.findById(src)).willReturn(Optional.of(source));
        EstimateItem markup = EstimateItem.builder()
                .id(UUID.randomUUID()).type(ItemType.WORK).name("Націнка")
                .unit(Unit.PERCENT).quantity(new BigDecimal("20.000")).unitPrice(BigDecimal.ZERO)
                .percentBaseKind(PercentBaseKind.POSITION).percentBaseItemId(baseId)
                .lineTotal(new BigDecimal("200.00")).sortOrder(0)
                .estimate(source)
                .build();
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src)).willReturn(List.of(markup));
        given(itemRepository.findById(baseId)).willReturn(Optional.of(
                EstimateItem.builder().id(baseId).name("Шафа").build()));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(src), ownerId);

        assertThat(resp.items().get(0).baseOriginLabel())
                .isEqualTo("+20% від «Шафа» · кошторис «Санвузол»");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consolidate_unnamedSourceEstimate_labelUsesTheSameDatedDefaultThePwaShows() {
        // Regression: buildBaseOriginLabel used to fall back to the bare word «Кошторис» for an
        // unnamed source (estimates.name is nullable) — every unnamed source then read identically,
        // and the whole point of the label (telling WHICH estimate a frozen line came from) was lost.
        UUID src = UUID.randomUUID();
        UUID consolidatedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> {
            Estimate e = inv.getArgument(0);
            e.setId(consolidatedId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        Estimate source = sourceEstimate(src, ownerId); // name left null (unnamed)
        source.setCreatedAt(Instant.parse("2026-07-06T10:00:00Z")); // 13:00 Kyiv, still 6 July
        given(estimateRepository.findById(src)).willReturn(Optional.of(source));
        EstimateItem discount = EstimateItem.builder()
                .id(UUID.randomUUID()).type(ItemType.WORK).name("Знижка")
                .unit(Unit.PERCENT).quantity(new BigDecimal("-5.000")).unitPrice(BigDecimal.ZERO)
                .percentBaseKind(PercentBaseKind.TOTAL).lineTotal(new BigDecimal("-50.00")).sortOrder(0)
                .estimate(source)
                .build();
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(src)).willReturn(List.of(discount));
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(consolidatedId)).willAnswer(inv -> saved[0]);

        EstimateResponse resp = estimateService.consolidate(projectId, "Зведений", List.of(src), ownerId);

        // «Кошторис від 6 липня» — exactly what the PWA's estimateName() would show for this
        // same (unnamed) estimate in the list, not the bare, indistinguishable «Кошторис».
        assertThat(resp.items().get(0).baseOriginLabel())
                .isEqualTo("-5% від робіт · кошторис «Кошторис від 6 липня»");
    }

    @Test
    void setCountInEconomy_togglesFlagInAnyStatus() {
        Estimate signed = signedEstimate();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());

        estimateService.setCountInEconomy(estimateId, false, ownerId);
        assertThat(signed.isCountInEconomy()).isFalse();
        estimateService.setCountInEconomy(estimateId, true, ownerId);
        assertThat(signed.isCountInEconomy()).isTrue();
    }

    // ---- appendItems (receipt import) --------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void appendItems_appendsAfterExistingWithContinuedSortOrder() {
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        EstimateItem existing = item(ItemType.WORK, "Наявна", "1", "100");
        existing.setSortOrder(4);
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(List.of(existing)) // first call: compute max sortOrder
                .willAnswer(inv -> {           // second call: toResponse
                    List<EstimateItem> all = new ArrayList<>();
                    all.add(existing);
                    all.addAll(saved[0]);
                    return all;
                });

        List<EstimateService.ImportEstimateData.ImportItem> items = List.of(
                new EstimateService.ImportEstimateData.ImportItem(
                        ItemType.MATERIAL, "Цемент", null, Unit.PIECE,
                        new BigDecimal("2"), new BigDecimal("80")));

        EstimateResponse resp = estimateService.appendItems(estimateId, items, ownerId);

        assertThat(saved[0]).extracting(EstimateItem::getSortOrder).containsExactly(5); // continues 4 → 5
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.total()).isEqualByComparingTo("260.00"); // 100 + 2×80
    }

    @Test
    void appendItems_rejectsSignedEstimate() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        assertThatThrownBy(() -> estimateService.appendItems(estimateId, List.of(
                new EstimateService.ImportEstimateData.ImportItem(
                        ItemType.MATERIAL, "X", null, Unit.PIECE,
                        new BigDecimal("1"), new BigDecimal("1"))), ownerId))
                .isInstanceOf(EstimateSignedException.class);
        verify(itemRepository, never()).saveAll(anyList());
    }

    // ---- fixtures ---------------------------------------------------------

    private Estimate sourceEstimate(UUID id, UUID userId) {
        // Shares the fixed projectId (via ownedProject) so it passes the same-project check.
        return Estimate.builder()
                .id(id)
                .project(ownedProject(userId))
                .status(EstimateStatus.DRAFT)
                .createdAt(Instant.now())
                .build();
    }

    private CatalogItem catalogItem(String name, ItemType type, Unit unit, String price, String category) {
        return CatalogItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .category(category)
                .type(type)
                .unit(unit)
                .defaultPrice(new BigDecimal(price))
                .build();
    }

    private Estimate signedEstimate() {
        Estimate estimate = ownedEstimate(ownerId);
        estimate.setStatus(EstimateStatus.SIGNED);
        estimate.setSignedAt(Instant.now());
        estimate.setSignerName("Олена Іваненко");
        return estimate;
    }

    @Test
    @SuppressWarnings("unchecked")
    void createFromImport_persistsItems() {
        // A detected deposit is no longer carried on the estimate — EstimateImportService
        // turns it into a project_payment instead (see PaymentServiceTest).
        Project project = ownedProject(ownerId);
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project);
        given(estimateRepository.save(any(Estimate.class))).willAnswer(invocation -> {
            Estimate e = invocation.getArgument(0);
            e.setId(estimateId);
            e.setStatus(EstimateStatus.DRAFT);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            return e;
        });
        // toResponse re-reads the items after saveAll — return what was saved.
        List<EstimateItem>[] saved = new List[1];
        given(itemRepository.saveAll(anyList())).willAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willAnswer(invocation -> saved[0]);

        var data = new EstimateService.ImportEstimateData(
                "Import",
                List.of(
                        new EstimateService.ImportEstimateData.ImportItem(
                                ItemType.WORK, "Малярні роботи", "Кімната", Unit.M2,
                                new BigDecimal("2"), new BigDecimal("100")),
                        new EstimateService.ImportEstimateData.ImportItem(
                                ItemType.MATERIAL, "Клей", null, Unit.PIECE,
                                new BigDecimal("3"), new BigDecimal("50"))));

        EstimateResponse response = estimateService.createFromImport(projectId, data, ownerId);

        assertThat(response.items()).hasSize(2);
        assertThat(response.worksSubtotal()).isEqualByComparingTo("200.00");
        assertThat(response.materialsSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.total()).isEqualByComparingTo("350.00");
        verify(limitService).requireCanAddEstimate(ownerId, projectId); // FREE cap still enforced
        verify(projectRepository).incrementEstimatesCreated(projectId); // lifetime churn counter
    }

    private Project ownedProject(UUID userId) {
        User owner = User.builder().id(userId).build();
        Project project = Project.builder().id(projectId).owner(owner).build();
        return project;
    }

    // ---- offline authoring: client-provided estimate UUID → idempotent createForProject ---------

    @Test
    void createForProject_withRequestedId_persistsThatId() {
        UUID requestedId = UUID.randomUUID();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.findById(requestedId)).willReturn(Optional.empty());
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> inv.getArgument(0));

        var resp = estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, null, null), ownerId, requestedId);

        assertThat(resp.id()).isEqualTo(requestedId);
    }

    @Test
    void createForProject_withRequestedId_existing_isIdempotentAndSkipsLimit() {
        UUID requestedId = UUID.randomUUID();
        Estimate existing = Estimate.builder()
                .id(requestedId).project(ownedProject(ownerId)).status(EstimateStatus.DRAFT)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.findById(requestedId)).willReturn(Optional.of(existing));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(requestedId)).willReturn(List.of());

        var resp = estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, null, null), ownerId, requestedId);

        assertThat(resp.id()).isEqualTo(requestedId);
        verify(estimateRepository, never()).save(any(Estimate.class));
        verify(limitService, never()).requireCanAddEstimate(any(), any());
    }

    @Test
    void createForProject_withRequestedId_belongsToAnotherProject_throwsAccessDenied() {
        UUID requestedId = UUID.randomUUID();
        Project otherProject = Project.builder().id(UUID.randomUUID())
                .owner(User.builder().id(ownerId).build()).build();
        Estimate foreign = Estimate.builder()
                .id(requestedId).project(otherProject).status(EstimateStatus.DRAFT).build();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(ownedProject(ownerId));
        given(estimateRepository.findById(requestedId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> estimateService.createForProject(
                projectId, new EstimateCreateRequest(null, null, null), ownerId, requestedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- offline authoring: client-provided item UUID → idempotent add; idempotent delete -------

    @Test
    void addItem_withRequestedId_persistsThatId() {
        UUID requestedId = UUID.randomUUID();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        given(itemRepository.findById(requestedId)).willReturn(Optional.empty());
        given(itemRepository.save(any(EstimateItem.class))).willAnswer(inv -> inv.getArgument(0));

        var resp = estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, "Робота", null, Unit.M2,
                new BigDecimal("2"), new BigDecimal("100"), 0, null, false, null, null), ownerId, requestedId);

        assertThat(resp.id()).isEqualTo(requestedId);
    }

    @Test
    void addItem_withRequestedId_alreadyInThisEstimate_isIdempotentNoInsert() {
        UUID requestedId = UUID.randomUUID();
        Estimate estimate = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));
        EstimateItem existing = EstimateItem.builder()
                .id(requestedId).estimate(estimate).type(ItemType.WORK).name("Вже є").unit(Unit.M2)
                .quantity(new BigDecimal("1")).unitPrice(new BigDecimal("50")).sortOrder(0).build();
        given(itemRepository.findById(requestedId)).willReturn(Optional.of(existing));

        var resp = estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, "Дубль", null, Unit.M2,
                new BigDecimal("9"), new BigDecimal("9"), 0, null, false, null, null), ownerId, requestedId);

        assertThat(resp.id()).isEqualTo(requestedId);
        assertThat(resp.name()).isEqualTo("Вже є");
        verify(itemRepository, never()).save(any(EstimateItem.class));
    }

    @Test
    void addItem_withRequestedId_belongsToAnotherEstimate_throwsAccessDenied() {
        UUID requestedId = UUID.randomUUID();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        Estimate other = Estimate.builder().id(UUID.randomUUID()).status(EstimateStatus.DRAFT).build();
        EstimateItem foreign = EstimateItem.builder()
                .id(requestedId).estimate(other).type(ItemType.WORK).name("Чужий").unit(Unit.M2)
                .quantity(new BigDecimal("1")).unitPrice(new BigDecimal("1")).sortOrder(0).build();
        given(itemRepository.findById(requestedId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.M2,
                new BigDecimal("1"), new BigDecimal("1"), 0, null, false, null, null), ownerId, requestedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteItem_absentItem_isIdempotentNoOp() {
        UUID itemId = UUID.randomUUID();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        // deleteItem now delegates to the bulk path, so the lookup it makes is findAllById — the
        // single-item route stayed a route rather than a second implementation of the same rules.
        given(itemRepository.findAllById(List.of(itemId))).willReturn(List.of());

        assertThatCode(() -> estimateService.deleteItem(estimateId, itemId, ownerId)).doesNotThrowAnyException();
        verify(itemRepository, never()).deleteAll(anyList());
    }

    private Estimate ownedEstimate(UUID userId) {
        Project project = ownedProject(userId);
        return Estimate.builder()
                .id(estimateId)
                .project(project)
                .status(EstimateStatus.DRAFT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private EstimateItem item(ItemType type, String name, String qty, String price) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .type(type)
                .name(name)
                .unit(Unit.PIECE)
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(price))
                // The STORED amount (V88). Reads sum this column and compute nothing — that is what
                // keeps a signed estimate from drifting — so a fixture without it totals to zero.
                .lineTotal(new BigDecimal(qty).multiply(new BigDecimal(price))
                        .setScale(2, java.math.RoundingMode.HALF_UP))
                .sortOrder(0)
                .build();
    }

    // ---- reordering after a drag ----------------------------------------------------------------
    //
    // Sections are not rows: a section IS the lines sharing a category, ordered by the first of them.
    // So dragging a line, and dragging a whole section, are the same operation — renumbering
    // sortOrder — and the request states the WHOLE arrangement rather than a move, which is what
    // makes it safe for the offline queue to replay.

    @Test
    void reorderItems_takesSortOrderFromThePositionInTheList() {
        EstimateItem a = item(ItemType.WORK, "A", "1", "10");
        EstimateItem b = item(ItemType.WORK, "B", "1", "10");
        EstimateItem c = item(ItemType.WORK, "C", "1", "10");
        givenItems(a, b, c);

        estimateService.reorderItems(estimateId, order(c, a, b), ownerId);

        assertThat(c.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(2);
    }

    @Test
    void reorderItems_movesALineIntoAnotherSectionInTheSameOperation() {
        // Dragging across sections changes the category AND the position. Two requests could
        // half-apply and leave a line sorted into a section it is not in.
        EstimateItem tiling = item(ItemType.WORK, "Укладання", "1", "10");
        tiling.setCategory("Плитка");
        EstimateItem prep = item(ItemType.WORK, "Грунтування", "1", "10");
        prep.setCategory("Підготовка");
        givenItems(prep, tiling);

        estimateService.reorderItems(estimateId, new EstimateItemsOrderRequest(List.of(
                new EstimateItemsOrderRequest.Line(prep.getId(), "Підготовка"),
                new EstimateItemsOrderRequest.Line(tiling.getId(), "Підготовка"))), ownerId);

        assertThat(tiling.getCategory()).isEqualTo("Підготовка");
        assertThat(tiling.getSortOrder()).isEqualTo(1);
    }

    @Test
    void reorderItems_keepsALineTheRequestNeverMentioned() {
        // Added on another device after this arrangement was captured. Dropping it would lose work
        // the master can see on the other screen; leaving its sortOrder alone would collide.
        EstimateItem a = item(ItemType.WORK, "A", "1", "10");
        EstimateItem b = item(ItemType.WORK, "B", "1", "10");
        EstimateItem unseen = item(ItemType.WORK, "Додана на телефоні", "1", "10");
        givenItems(a, b, unseen);

        estimateService.reorderItems(estimateId, order(b, a), ownerId);

        assertThat(b.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(unseen.getSortOrder()).as("лишається, після перелічених").isEqualTo(2);
    }

    @Test
    void reorderItems_skipsAnIdThatIsAlreadyGone() {
        // The queue may replay an arrangement naming a line deleted since. Failing forever over it
        // would wedge every later operation behind it.
        EstimateItem a = item(ItemType.WORK, "A", "1", "10");
        givenItems(a);

        assertThatCode(() -> estimateService.reorderItems(estimateId, new EstimateItemsOrderRequest(List.of(
                new EstimateItemsOrderRequest.Line(UUID.randomUUID(), null),
                new EstimateItemsOrderRequest.Line(a.getId(), null))), ownerId))
                .doesNotThrowAnyException();
        assertThat(a.getSortOrder()).isZero();
    }

    @Test
    void reorderItems_isIdempotent() {
        EstimateItem a = item(ItemType.WORK, "A", "1", "10");
        EstimateItem b = item(ItemType.WORK, "B", "1", "10");
        givenItems(a, b);

        estimateService.reorderItems(estimateId, order(b, a), ownerId);
        estimateService.reorderItems(estimateId, order(b, a), ownerId);

        assertThat(b.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
    }

    @Test
    void reorderItems_refusesOnASignedEstimate() {
        Estimate signed = ownedEstimate(ownerId);
        signed.setStatus(EstimateStatus.SIGNED);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));

        assertThatThrownBy(() -> estimateService.reorderItems(
                estimateId, new EstimateItemsOrderRequest(List.of(
                        new EstimateItemsOrderRequest.Line(UUID.randomUUID(), null))), ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    /** Both reads of the item list in reorderItems see the same (mutated in place) objects. */
    private void givenItems(EstimateItem... items) {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(new ArrayList<>(List.of(items)));
    }

    private EstimateItemsOrderRequest order(EstimateItem... items) {
        return new EstimateItemsOrderRequest(java.util.Arrays.stream(items)
                .map(i -> new EstimateItemsOrderRequest.Line(i.getId(), i.getCategory()))
                .toList());
    }

    // ---- duplicate with a markup (the бригадир's two prices) -------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_marksUpTheWORKSbyDEFAULTandLEAVESmaterialsAlone() {
        // A foreman marks up his labour. Materials are bought at cost and passed through, so
        // marking them up by default would inflate the client's estimate in a way he never asked
        // for — he ticks a material himself if he wants one raised.
        EstimateItem work = item(ItemType.WORK, "Укладання плитки", "10", "350");
        EstimateItem material = item(ItemType.MATERIAL, "Клей", "5", "400");
        givenItems(work, material);
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> inv.getArgument(0));

        estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, new BigDecimal("15"), false, null), ownerId);

        ArgumentCaptor<List<EstimateItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(saved.capture());
        List<EstimateItem> copies = saved.getValue();
        // 350 × 1.15 = 402.5 → whole hryvnia, because that is what a client is quoted.
        assertThat(copies.get(0).getUnitPrice()).isEqualByComparingTo("403");
        assertThat(copies.get(1).getUnitPrice()).as("матеріал лишається за собівартістю")
                .isEqualByComparingTo("400");
        // EVERY line records what it cost, marked up or not: a passthrough line earns nothing today,
        // and if the master raises it by hand later that difference is real margin.
        assertThat(copies.get(0).getSourceUnitPrice()).isEqualByComparingTo("350");
        assertThat(copies.get(1).getSourceUnitPrice()).isEqualByComparingTo("400");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_marksUpONLYtheLINESaskedFor() {
        EstimateItem chosen = item(ItemType.WORK, "Обрана", "1", "100");
        EstimateItem other = item(ItemType.WORK, "Не обрана", "1", "100");
        givenItems(chosen, other);
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> inv.getArgument(0));

        estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, new BigDecimal("20"), false, List.of(chosen.getId())),
                ownerId);

        ArgumentCaptor<List<EstimateItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getUnitPrice()).isEqualByComparingTo("120");
        assertThat(saved.getValue().get(1).getUnitPrice()).isEqualByComparingTo("100");
    }

    @Test
    void duplicate_stopsTheSOURCEfromCountingInTheEconomy() {
        // The source is what the foreman PAYS. Left counted, the object economy would report his
        // crew's wages as his income — the exact double-count this feature exists to remove.
        Estimate source = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(source));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(new ArrayList<>());
        given(estimateRepository.save(any(Estimate.class))).willAnswer(inv -> inv.getArgument(0));
        assertThat(source.isCountInEconomy()).isTrue();

        estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, new BigDecimal("10"), false, null), ownerId);

        assertThat(source.isCountInEconomy()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_discount_SUBTRACTStheDiscountFromWorks_leavesMaterials_andStoresSignedPercent() {
        // Уцінка is markup mirrored: the SAME copy, only the works go DOWN by the percent. The stored
        // markup_percent is negative so the name «… -15%» and the economy hint read the direction.
        EstimateItem work = item(ItemType.WORK, "Укладання плитки", "10", "350");
        EstimateItem material = item(ItemType.MATERIAL, "Клей", "5", "400");
        givenItems(work, material);
        ArgumentCaptor<Estimate> savedCopy = ArgumentCaptor.forClass(Estimate.class);
        given(estimateRepository.save(savedCopy.capture())).willAnswer(inv -> inv.getArgument(0));

        estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, new BigDecimal("15"), true, null), ownerId);

        ArgumentCaptor<List<EstimateItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(saved.capture());
        List<EstimateItem> copies = saved.getValue();
        // 350 × 0.85 = 297.5 → 298 whole hryvnia; the material stays at cost.
        assertThat(copies.get(0).getUnitPrice()).isEqualByComparingTo("298");
        assertThat(copies.get(1).getUnitPrice()).as("матеріал лишається за собівартістю")
                .isEqualByComparingTo("400");
        // Source price recorded on every line, exactly as for a markup.
        assertThat(copies.get(0).getSourceUnitPrice()).isEqualByComparingTo("350");
        assertThat(savedCopy.getValue().getMarkupPercent())
                .as("знак несе напрямок — уцінка зберігається відʼємною")
                .isEqualByComparingTo("-15");
    }

    // ---- deleting many lines at once, and the cascade into the copies ---------------------------

    /**
     * A line that really belongs to the estimate under test.
     *
     * `deleteItems` filters on `item.getEstimate()`, which the plain {@link #item} helper leaves
     * null — fine for the reorder tests, which never look at it, and an NPE here. The link is NOT
     * NULL in the database, so the realistic object is the one worth testing against; making the
     * service tolerate a null there would only have hidden this.
     */
    private EstimateItem ownedLine(String name, String price) {
        EstimateItem line = item(ItemType.WORK, name, "1", price);
        line.setEstimate(ownedEstimate(ownerId));
        return line;
    }

    /** deleteItems reads findAllById, never the ordered list — stubbing that would be unused. */
    private void givenOwnedEstimate() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
    }

    @Test
    void deleteItems_removesTheTWINSinAnUNSIGNEDduplicate() {
        // Trimming happens in the master-price sheet — that is where a 167-position template was
        // applied. A copy that kept the removed positions would put them back in front of the client.
        EstimateItem parentLine = ownedLine("Зайва", "100");
        givenOwnedEstimate();
        UUID copyId = UUID.randomUUID();
        Estimate copy = Estimate.builder().id(copyId).status(EstimateStatus.DRAFT)
                .duplicatedFromId(estimateId).build();
        EstimateItem twin = item(ItemType.WORK, "Зайва", "1", "115");
        given(estimateRepository.findByDuplicatedFromId(estimateId)).willReturn(List.of(copy));
        given(itemRepository.findAllById(List.of(parentLine.getId()))).willReturn(List.of(parentLine));
        given(itemRepository.findByEstimateIdAndSourceItemIdIn(copyId, List.of(parentLine.getId())))
                .willReturn(List.of(twin));

        estimateService.deleteItems(estimateId, List.of(parentLine.getId()), ownerId);

        verify(itemRepository).deleteAll(List.of(twin));
        verify(itemRepository).deleteAll(List.of(parentLine));
    }

    @Test
    void deleteItems_leavesASIGNEDduplicateALONE() {
        // A signature certifies an exact set of lines and totals. That outranks the convenience of
        // keeping the two sheets in step — and it is why the cascade is here and not an ON DELETE
        // CASCADE in the database, which could not know the copy was signed.
        EstimateItem parentLine = ownedLine("Зайва", "100");
        givenOwnedEstimate();
        Estimate signedCopy = Estimate.builder().id(UUID.randomUUID())
                .status(EstimateStatus.SIGNED).duplicatedFromId(estimateId).build();
        given(estimateRepository.findByDuplicatedFromId(estimateId)).willReturn(List.of(signedCopy));
        given(itemRepository.findAllById(List.of(parentLine.getId()))).willReturn(List.of(parentLine));

        estimateService.deleteItems(estimateId, List.of(parentLine.getId()), ownerId);

        verify(itemRepository, never()).findByEstimateIdAndSourceItemIdIn(any(), any());
        verify(itemRepository).deleteAll(List.of(parentLine));
    }

    @Test
    void deleteItems_ignoresIdsThatAreAlreadyGone() {
        // The offline queue replays; a second delete of the same lines must be a no-op, not a 404.
        givenOwnedEstimate();
        given(itemRepository.findAllById(anyList())).willReturn(List.of());

        estimateService.deleteItems(estimateId, List.of(UUID.randomUUID()), ownerId);

        verify(itemRepository, never()).deleteAll(anyList());
    }
}
