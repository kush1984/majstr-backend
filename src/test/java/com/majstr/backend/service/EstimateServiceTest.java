package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.exception.InvalidEstimateStatusException;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EstimateServiceTest {

    @Mock private EstimateRepository estimateRepository;
    @Mock private EstimateItemRepository itemRepository;
    @Mock private ProjectService projectService;
    @Mock private CatalogService catalogService;

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
                projectId, new EstimateCreateRequest(null, "kickoff"), ownerId);

        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.status()).isEqualTo(EstimateStatus.DRAFT);
        assertThat(response.items()).isEmpty();
        assertThat(response.worksSubtotal()).isEqualByComparingTo("0.00");
        assertThat(response.materialsSubtotal()).isEqualByComparingTo("0.00");
        assertThat(response.total()).isEqualByComparingTo("0.00");
        verify(estimateRepository).save(any(Estimate.class));
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
                new BigDecimal("25.000"), new BigDecimal("180.00"), 1);

        var resp = estimateService.addItem(estimateId, req, ownerId);

        assertThat(resp.lineTotal()).isEqualByComparingTo("4500.00");
        assertThat(resp.type()).isEqualTo(ItemType.WORK);
        // Category is normalized (trimmed) on write.
        assertThat(resp.category()).isEqualTo("Walls");
    }

    @Test
    void addItem_rejectsWhenEstimateBelongsToAnotherUser() {
        Estimate estimate = ownedEstimate(otherUserId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(estimate));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- signed estimates are immutable ------------------------------------

    @Test
    void update_rejectsWhenEstimateIsSigned() {
        Estimate signed = signedEstimate();
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signed));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.DRAFT, null, null), ownerId))
                .isInstanceOf(EstimateSignedException.class);
        assertThat(signed.getStatus()).isEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void update_rejectsManualTransitionToSigned() {
        Estimate draft = ownedEstimate(ownerId);
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> estimateService.update(
                estimateId, new EstimateUpdateRequest(EstimateStatus.SIGNED, null, null), ownerId))
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
                estimateId, new EstimateUpdateRequest(EstimateStatus.REJECTED, null, null), ownerId);

        assertThat(resp.status()).isEqualTo(EstimateStatus.REJECTED);
    }

    @Test
    void addItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0);

        assertThatThrownBy(() -> estimateService.addItem(estimateId, req, ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    @Test
    void updateItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        EstimateItemRequest req = new EstimateItemRequest(
                ItemType.WORK, "X", null, Unit.PIECE,
                new BigDecimal("1.000"), new BigDecimal("1.00"), 0);

        assertThatThrownBy(() -> estimateService.updateItem(estimateId, UUID.randomUUID(), req, ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    @Test
    void deleteItem_rejectsWhenEstimateIsSigned() {
        given(estimateRepository.findById(estimateId)).willReturn(Optional.of(signedEstimate()));

        assertThatThrownBy(() -> estimateService.deleteItem(estimateId, UUID.randomUUID(), ownerId))
                .isInstanceOf(EstimateSignedException.class);
    }

    // ---- fixtures ---------------------------------------------------------

    private Estimate signedEstimate() {
        Estimate estimate = ownedEstimate(ownerId);
        estimate.setStatus(EstimateStatus.SIGNED);
        estimate.setSignedAt(Instant.now());
        estimate.setSignerName("Олена Іваненко");
        return estimate;
    }

    private Project ownedProject(UUID userId) {
        User owner = User.builder().id(userId).build();
        Project project = Project.builder().id(projectId).owner(owner).build();
        return project;
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
