package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.TemplateTradeOverride;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.TemplateTradeOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Estimate templates — ready-made bundles of works for a typical job. Lists the
 * system defaults relevant to a master plus their own saved templates, lets a
 * master save the current estimate as a template, and applies a template into a
 * new editable estimate (names+units in, quantities empty, prices looked up from
 * the master's own catalog by name).
 */
@Service
@RequiredArgsConstructor
public class EstimateTemplateService {

    private final EstimateTemplateRepository templateRepository;
    private final EstimateTemplateItemRepository templateItemRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final CatalogItemRepository catalogRepository;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final LimitService limitService;
    private final EstimateService estimateService;
    private final TemplateTradeOverrideRepository tradeOverrideRepository;

    // ---- listing -----------------------------------------------------------

    /**
     * The picker: system defaults relevant to the master's trades (+ general),
     * followed by the master's own templates. Item counts come from one grouped
     * query (no N+1). {@code user} must be loaded with trades eager-fetched.
     */
    @Transactional(readOnly = true)
    public List<EstimateTemplateSummary> listForUser(User user) {
        Set<Trade> trades = user.getTrades();
        // A master may have re-filed a default into one of THEIR trades — fetch those
        // templates too, or a re-filed one would vanish from the list it was moved into.
        Map<UUID, Optional<Trade>> overrides = overridesOf(user.getId());
        List<EstimateTemplate> defaults = trades.isEmpty() && overrides.isEmpty()
                ? List.of()
                : templateRepository.findDefaultsForTradesOrIds(trades, overrides.keySet());
        List<EstimateTemplate> own = templateRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());

        List<EstimateTemplate> all = new ArrayList<>(defaults);
        all.addAll(own);
        Map<UUID, Long> counts = itemCounts(all);

        return all.stream()
                .map(t -> new EstimateTemplateSummary(
                        t.getId(), t.getName(), effectiveTrade(t, overrides), t.isDefault(),
                        counts.getOrDefault(t.getId(), 0L).intValue()))
                .toList();
    }

    /** The master's own filings, {@code templateId → trade (possibly null = general)}. */
    private Map<UUID, Optional<Trade>> overridesOf(UUID userId) {
        Map<UUID, Optional<Trade>> map = new java.util.LinkedHashMap<>();
        for (TemplateTradeOverride o : tradeOverrideRepository.findByUserId(userId)) {
            map.put(o.getTemplateId(), Optional.ofNullable(o.getTrade()));
        }
        return map;
    }

    /** The master's own filing wins over the template's shipped trade. */
    private static Trade effectiveTrade(EstimateTemplate t, Map<UUID, Optional<Trade>> overrides) {
        Optional<Trade> override = overrides.get(t.getId());
        return override != null ? override.orElse(null) : t.getTrade();
    }

    /**
     * Re-file a template into a trade. An OWN template carries the trade on its own row;
     * a system default is shared, so the change is stored as this master's own override
     * and stays invisible to everyone else.
     */
    @Transactional
    public EstimateTemplateSummary setTrade(UUID templateId, Trade trade, UUID ownerId) {
        EstimateTemplate template = loadAccessible(templateId, ownerId);
        if (!template.isDefault()) {
            template.setTrade(trade);
            tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, templateId)
                    .ifPresent(tradeOverrideRepository::delete);
        } else {
            TemplateTradeOverride row = tradeOverrideRepository
                    .findByUserIdAndTemplateId(ownerId, templateId)
                    .orElseGet(() -> TemplateTradeOverride.builder()
                            .userId(ownerId).templateId(templateId).build());
            row.setTrade(trade);
            tradeOverrideRepository.save(row);
        }
        int count = templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId).size();
        return new EstimateTemplateSummary(
                template.getId(), template.getName(), trade, template.isDefault(), count);
    }

    @Transactional(readOnly = true)
    public EstimateTemplateDetail get(UUID templateId, UUID ownerId) {
        EstimateTemplate template = loadAccessible(templateId, ownerId);
        return EstimateTemplateDetail.from(
                template,
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId));
    }

    // ---- own templates -----------------------------------------------------

    /**
     * Saves the current estimate as the master's own template. Names + units +
     * type + order are kept; quantities and prices are dropped (a template is
     * object-agnostic). {@code trade} files it under a trade (null = general).
     */
    @Transactional
    public EstimateTemplateSummary saveFromEstimate(UUID estimateId, String name, Trade trade, UUID ownerId) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        User owner = estimate.getProject().getOwner();
        List<EstimateItem> items = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        EstimateTemplate template = templateRepository.save(EstimateTemplate.builder()
                .owner(owner)
                .name(name.trim())
                .trade(trade)
                .isDefault(false)
                .build());
        List<EstimateTemplateItem> toSave = new ArrayList<>();
        for (EstimateItem item : items) {
            toSave.add(EstimateTemplateItem.builder()
                    .template(template)
                    .name(item.getName())
                    .type(item.getType())
                    .unit(item.getUnit())
                    .sortOrder(item.getSortOrder())
                    .build());
        }
        templateItemRepository.saveAll(toSave);
        return new EstimateTemplateSummary(
                template.getId(), template.getName(), trade, false, toSave.size());
    }

    @Transactional
    public EstimateTemplateSummary rename(UUID templateId, String name, UUID ownerId) {
        EstimateTemplate template = loadOwnTemplate(templateId, ownerId);
        template.setName(name.trim());
        long count = templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId).size();
        return new EstimateTemplateSummary(
                template.getId(), template.getName(), template.getTrade(), false, (int) count);
    }

    @Transactional
    public void delete(UUID templateId, UUID ownerId) {
        // Idempotent: a replayed offline delete of an already-gone template is a no-op, not a 404.
        templateRepository.findById(templateId).ifPresent(t -> {
            loadOwnTemplate(templateId, ownerId); // ownership + "not a read-only default" check
            templateRepository.delete(t); // items cascade (FK ON DELETE CASCADE)
        });
    }

    /** Add a position to my own template (appended last). Defaults are read-only. */
    @Transactional
    public EstimateTemplateDetail addItem(UUID templateId, TemplateItemRequest req, UUID ownerId) {
        return addItem(templateId, req, ownerId, null);
    }

    /**
     * Add a position, optionally with a CLIENT-PROVIDED id (offline authoring) — idempotent on
     * replay; an id that already lives in a DIFFERENT template is rejected.
     */
    @Transactional
    public EstimateTemplateDetail addItem(UUID templateId, TemplateItemRequest req, UUID ownerId, UUID requestedId) {
        EstimateTemplate template = loadOwnTemplate(templateId, ownerId);
        List<EstimateTemplateItem> items =
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId);
        if (requestedId != null) {
            var existing = templateItemRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getTemplate().getId().equals(templateId)) {
                    throw new AccessDeniedException("Template item belongs to a different template");
                }
                return EstimateTemplateDetail.from(template, items); // idempotent replay
            }
        }
        int nextSort = items.isEmpty() ? 0 : items.get(items.size() - 1).getSortOrder() + 1;
        EstimateTemplateItem item = templateItemRepository.save(EstimateTemplateItem.builder()
                .id(requestedId)
                .template(template)
                .name(req.name().trim())
                .type(req.type())
                .unit(req.unit())
                .sortOrder(nextSort)
                .build());
        items.add(item);
        return EstimateTemplateDetail.from(template, items);
    }

    /** Remove a position from my own template. Defaults are read-only. */
    @Transactional
    public EstimateTemplateDetail removeItem(UUID templateId, UUID itemId, UUID ownerId) {
        EstimateTemplate template = loadOwnTemplate(templateId, ownerId);
        // Idempotent: a replayed offline removal of an already-gone position is a no-op, not a 404.
        templateItemRepository.findById(itemId)
                .filter(i -> i.getTemplate().getId().equals(templateId))
                .ifPresent(templateItemRepository::delete);
        return EstimateTemplateDetail.from(
                template,
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId));
    }

    // ---- apply -------------------------------------------------------------

    /**
     * Creates a new estimate in the project from the template. Each template item
     * becomes a real {@link EstimateItem}: the name + unit are copied, the quantity
     * starts at zero (the master fills it per object), and the price is taken from
     * the master's OWN catalog by name match (type/unit/category too), or left at
     * zero with no catalog match. The result is an ordinary, fully editable estimate.
     */
    @Transactional
    public EstimateResponse applyToProject(UUID projectId,
                                           UUID templateId,
                                           EstimateCreateRequest req,
                                           UUID ownerId) {
        EstimateTemplate template = loadAccessible(templateId, ownerId);
        Project project = projectService.loadOwned(projectId, ownerId);
        limitService.requireCanAddEstimate(ownerId, projectId);

        // Master's catalog by lowercased name → price/type/unit at apply-time.
        Map<String, CatalogItem> catalog = catalogRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .collect(Collectors.toMap(c -> c.getName().toLowerCase(), c -> c, (a, b) -> a));

        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project)
                .name(normalize(req.name()))
                .validUntil(req.validUntil())
                .notes(normalize(req.notes()))
                .build());

        List<EstimateTemplateItem> templateItems =
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId);
        List<EstimateItem> toSave = new ArrayList<>();
        for (EstimateTemplateItem ti : templateItems) {
            CatalogItem match = catalog.get(ti.getName().toLowerCase());
            toSave.add(EstimateItem.builder()
                    .estimate(estimate)
                    .type(match != null ? match.getType() : ti.getType())
                    .name(ti.getName())
                    .category(match != null ? match.getCategory() : null)
                    .unit(match != null ? match.getUnit() : ti.getUnit())
                    .quantity(BigDecimal.ZERO) // empty — master fills per object
                    .unitPrice(match != null ? match.getDefaultPrice() : BigDecimal.ZERO)
                    .sortOrder(ti.getSortOrder())
                    .build());
        }
        estimateItemRepository.saveAll(toSave);
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter
        return estimateService.get(estimate.getId(), ownerId);
    }

    // ---- helpers -----------------------------------------------------------

    private Map<UUID, Long> itemCounts(List<EstimateTemplate> templates) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = templates.stream().map(EstimateTemplate::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : templateItemRepository.countByTemplateIds(ids)) {
            counts.put(row.getTemplateId(), row.getCnt());
        }
        return counts;
    }

    /** A default (shared) or the caller's own template — for read / apply. */
    private EstimateTemplate loadAccessible(UUID templateId, UUID ownerId) {
        EstimateTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate template not found: " + templateId));
        if (template.isDefault()) {
            return template;
        }
        if (template.getOwner() != null && template.getOwner().getId().equals(ownerId)) {
            return template;
        }
        throw new AccessDeniedException("Estimate template does not belong to the current user");
    }

    /** The caller's OWN template only — for rename / delete (defaults are read-only). */
    private EstimateTemplate loadOwnTemplate(UUID templateId, UUID ownerId) {
        EstimateTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate template not found: " + templateId));
        if (template.isDefault()
                || template.getOwner() == null
                || !template.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Estimate template does not belong to the current user");
        }
        return template;
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
