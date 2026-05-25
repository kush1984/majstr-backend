package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateSummary;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EstimateService {

    /** Money is rounded to two decimal places (kopiykas) using HALF_UP. */
    static final int MONEY_SCALE = 2;
    static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository itemRepository;
    private final ProjectService projectService;
    private final CatalogService catalogService;

    // ---- estimates ---------------------------------------------------------

    @Transactional
    public EstimateResponse createForProject(UUID projectId, EstimateCreateRequest req, UUID ownerId) {
        Project project = projectService.loadOwned(projectId, ownerId);
        Estimate estimate = Estimate.builder()
                .project(project)
                .validUntil(req.validUntil())
                .notes(normalize(req.notes()))
                .build();
        Estimate saved = estimateRepository.save(estimate);
        return toResponse(saved, List.of());
    }

    @Transactional(readOnly = true)
    public List<EstimateSummary> listForProject(UUID projectId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        return estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(EstimateSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EstimateResponse get(UUID estimateId, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        return toResponse(estimate, items);
    }

    @Transactional
    public EstimateResponse update(UUID estimateId, EstimateUpdateRequest req, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        estimate.setStatus(req.status());
        estimate.setValidUntil(req.validUntil());
        estimate.setNotes(normalize(req.notes()));
        List<EstimateItem> items = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        return toResponse(estimate, items);
    }

    @Transactional
    public void delete(UUID estimateId, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        estimateRepository.delete(estimate);
    }

    // ---- items -------------------------------------------------------------

    @Transactional
    public EstimateItemResponse addItem(UUID estimateId, EstimateItemRequest req, UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .type(req.type())
                .name(req.name().trim())
                .unit(req.unit())
                .quantity(req.quantity())
                .unitPrice(req.unitPrice())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return EstimateItemResponse.from(itemRepository.save(item));
    }

    @Transactional
    public EstimateItemResponse addItemFromCatalog(UUID estimateId,
                                                   UUID catalogItemId,
                                                   EstimateItemFromCatalogRequest req,
                                                   UUID ownerId) {
        Estimate estimate = loadOwned(estimateId, ownerId);
        CatalogItem source = catalogService.loadOwned(catalogItemId, ownerId);
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .type(source.getType())
                .name(source.getName())
                .unit(source.getUnit())
                .quantity(req.quantity())
                .unitPrice(source.getDefaultPrice())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        return EstimateItemResponse.from(itemRepository.save(item));
    }

    @Transactional
    public EstimateItemResponse updateItem(UUID estimateId,
                                           UUID itemId,
                                           EstimateItemRequest req,
                                           UUID ownerId) {
        loadOwned(estimateId, ownerId);
        EstimateItem item = loadItemInEstimate(estimateId, itemId);
        item.setType(req.type());
        item.setName(req.name().trim());
        item.setUnit(req.unit());
        item.setQuantity(req.quantity());
        item.setUnitPrice(req.unitPrice());
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        return EstimateItemResponse.from(item);
    }

    @Transactional
    public void deleteItem(UUID estimateId, UUID itemId, UUID ownerId) {
        loadOwned(estimateId, ownerId);
        EstimateItem item = loadItemInEstimate(estimateId, itemId);
        itemRepository.delete(item);
    }

    // ---- helpers -----------------------------------------------------------

    Estimate loadOwned(UUID estimateId, UUID ownerId) {
        Estimate estimate = estimateRepository.findById(estimateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate not found: " + estimateId));
        if (!estimate.getProject().getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Estimate does not belong to the current user");
        }
        return estimate;
    }

    private EstimateItem loadItemInEstimate(UUID estimateId, UUID itemId) {
        EstimateItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate item not found: " + itemId));
        if (!item.getEstimate().getId().equals(estimateId)) {
            throw new ResourceNotFoundException("Estimate item not found in estimate " + estimateId);
        }
        return item;
    }

    EstimateResponse toResponse(Estimate estimate, List<EstimateItem> items) {
        List<EstimateItemResponse> itemDtos = items.stream()
                .map(EstimateItemResponse::from)
                .toList();
        // Round per line first, then sum — keeps the user-visible math
        // consistent: line totals add up to subtotals, subtotals add up to total.
        BigDecimal worksSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal materialsSubtotal = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (EstimateItem item : items) {
            BigDecimal lineTotal = item.getQuantity().multiply(item.getUnitPrice())
                    .setScale(MONEY_SCALE, MONEY_ROUNDING);
            if (item.getType() == ItemType.WORK) {
                worksSubtotal = worksSubtotal.add(lineTotal);
            } else {
                materialsSubtotal = materialsSubtotal.add(lineTotal);
            }
        }
        BigDecimal total = worksSubtotal.add(materialsSubtotal);
        return new EstimateResponse(
                estimate.getId(),
                estimate.getProject().getId(),
                estimate.getStatus(),
                estimate.getValidUntil(),
                estimate.getNotes(),
                estimate.getCreatedAt(),
                estimate.getUpdatedAt(),
                itemDtos,
                worksSubtotal,
                materialsSubtotal,
                total
        );
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
