package com.majstr.backend.service;

import com.majstr.backend.dto.AddCatalogItemsBatchRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.service.measurement.MeasurementService;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

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
                new BigDecimal("25.000"), new BigDecimal("180.00"), 1, null, false);

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
                new BigDecimal("100.00"), 0, refs, false);

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
                new BigDecimal("42.000"), new BigDecimal("100.00"), 0, refs, true); // manual

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
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- signed estimates are immutable ------------------------------------

    @Test
    void update_rejectsWhenEstimateIsSigned() {
        Estimate signed = signedEstimate();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.DRAFT, null, null, null, null), ownerId))
                .isInstanceOf(EstimateSignedException.class);
        assertThat(signed.getStatus()).isEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void update_rejectsManualTransitionToSigned() {
        Estimate draft = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.SIGNED, null, null, null, null), ownerId))
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
                estimateId, new EstimateUpdateRequest(EstimateStatus.REJECTED, null, null, null, null), ownerId);

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
                estimateId, new EstimateUpdateRequest(EstimateStatus.REJECTED, null, null, null, null), ownerId);

        assertThat(sent.isCountInEconomy()).isFalse();
    }

    @Test
    void update_setsDepositAndComputesBalance() {
        Estimate sent = ownedEstimate(ownerId);
        sent.setStatus(EstimateStatus.SENT);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(sent));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(List.of(item(ItemType.WORK, "Монтаж", "3", "100"))); // total 300

        EstimateResponse resp = estimateService.update(estimateId,
                new EstimateUpdateRequest(EstimateStatus.SENT, null, null, null, new BigDecimal("120")), ownerId);

        assertThat(resp.total()).isEqualByComparingTo("300.00");
        assertThat(resp.depositAmount()).isEqualByComparingTo("120.00");
        assertThat(resp.balance()).isEqualByComparingTo("180.00");
        assertThat(sent.getDepositAmount()).isEqualByComparingTo("120.00");
    }

    @Test
    void update_clearsDepositWhenNull_balanceEqualsTotal() {
        Estimate sent = ownedEstimate(ownerId);
        sent.setStatus(EstimateStatus.SENT);
        sent.setDepositAmount(new BigDecimal("50.00"));
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(sent));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(List.of(item(ItemType.WORK, "Монтаж", "2", "100"))); // total 200

        EstimateResponse resp = estimateService.update(estimateId,
                new EstimateUpdateRequest(EstimateStatus.SENT, null, null, null, null), ownerId);

        assertThat(resp.depositAmount()).isNull();
        assertThat(resp.balance()).isEqualByComparingTo("200.00");
        assertThat(sent.getDepositAmount()).isNull();
    }

    @Test
    void update_depositExceedingTotal_clampsBalanceToZero() {
        Estimate sent = ownedEstimate(ownerId);
        sent.setStatus(EstimateStatus.SENT);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(sent));
        given(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId))
                .willReturn(List.of(item(ItemType.WORK, "Монтаж", "1", "100"))); // total 100

        EstimateResponse resp = estimateService.update(estimateId,
                new EstimateUpdateRequest(EstimateStatus.SENT, null, null, null, new BigDecimal("150")), ownerId);

        assertThat(resp.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void addItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    @Test
    void updateItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0, null, false);

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
    void createFromImport_persistsItemsWithDepositAndBalance() {
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
                "Import", new BigDecimal("100"),
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
        assertThat(response.depositAmount()).isEqualByComparingTo("100.00");
        assertThat(response.balance()).isEqualByComparingTo("250.00"); // total − deposit
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
                new BigDecimal("2"), new BigDecimal("100"), 0, null, false), ownerId, requestedId);

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
                new BigDecimal("9"), new BigDecimal("9"), 0, null, false), ownerId, requestedId);

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
                new BigDecimal("1"), new BigDecimal("1"), 0, null, false), ownerId, requestedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteItem_absentItem_isIdempotentNoOp() {
        UUID itemId = UUID.randomUUID();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(ownedEstimate(ownerId)));
        given(itemRepository.findById(itemId)).willReturn(Optional.empty());

        assertThatCode(() -> estimateService.deleteItem(estimateId, itemId, ownerId)).doesNotThrowAnyException();
        verify(itemRepository, never()).delete(any(EstimateItem.class));
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
                .sortOrder(0)
                .build();
    }
}
