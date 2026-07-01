package com.majstr.backend.service;

import com.majstr.backend.dto.AdminEstimateTemplateRequest;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin editing of the shared DEFAULT estimate templates ({@code is_default=true},
 * {@code owner=null}). {@code ROLE_ADMIN} enforced on {@code /api/admin/**}.
 *
 * <p>Unlike the catalog, default estimate templates are applied <b>live</b> — a
 * master never copies them; {@code EstimateTemplateService.applyToProject} reads
 * the template's items from the DB every time it's applied. So every edit here
 * reaches all masters immediately, with no versioning and no restart. This service
 * only ever touches defaults — a master's own (non-default) templates are rejected
 * by {@link #loadDefault}.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminEstimateTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AdminEstimateTemplateService.class);

    private final EstimateTemplateRepository templateRepository;
    private final EstimateTemplateItemRepository itemRepository;

    @Transactional(readOnly = true)
    public List<EstimateTemplateSummary> list() {
        List<EstimateTemplate> defaults = templateRepository.findAllDefaults();
        Map<UUID, Long> counts = itemCounts(defaults);
        return defaults.stream()
                .map(t -> new EstimateTemplateSummary(
                        t.getId(), t.getName(), t.getTrade(), true,
                        counts.getOrDefault(t.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public EstimateTemplateDetail get(UUID id) {
        EstimateTemplate t = loadDefault(id);
        return EstimateTemplateDetail.from(t, itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id));
    }

    @Transactional
    public EstimateTemplateSummary create(AdminEstimateTemplateRequest req, String actor) {
        EstimateTemplate t = templateRepository.save(EstimateTemplate.builder()
                .name(req.name().trim())
                .trade(req.trade())
                .isDefault(true)
                .owner(null)
                .build());
        log.info("admin {} created default estimate template {} '{}'", actor, t.getId(), t.getName());
        return new EstimateTemplateSummary(t.getId(), t.getName(), t.getTrade(), true, 0);
    }

    @Transactional
    public EstimateTemplateSummary update(UUID id, AdminEstimateTemplateRequest req, String actor) {
        EstimateTemplate t = loadDefault(id);
        t.setName(req.name().trim());
        t.setTrade(req.trade());
        long count = itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id).size();
        log.info("admin {} updated default estimate template {} '{}'", actor, id, t.getName());
        return new EstimateTemplateSummary(id, t.getName(), t.getTrade(), true, (int) count);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        EstimateTemplate t = loadDefault(id);
        templateRepository.delete(t); // items cascade (FK ON DELETE CASCADE)
        log.info("admin {} deleted default estimate template {} '{}'", actor, id, t.getName());
    }

    @Transactional
    public EstimateTemplateDetail addItem(UUID id, TemplateItemRequest req, String actor) {
        EstimateTemplate t = loadDefault(id);
        List<EstimateTemplateItem> items = itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id);
        int nextSort = items.isEmpty() ? 0 : items.get(items.size() - 1).getSortOrder() + 1;
        EstimateTemplateItem item = itemRepository.save(EstimateTemplateItem.builder()
                .template(t)
                .name(req.name().trim())
                .type(req.type())
                .unit(req.unit())
                .sortOrder(nextSort)
                .build());
        items.add(item);
        log.info("admin {} added item '{}' to default estimate template {}", actor, item.getName(), id);
        return EstimateTemplateDetail.from(t, items);
    }

    @Transactional
    public EstimateTemplateDetail updateItem(UUID id, UUID itemId, TemplateItemRequest req, String actor) {
        EstimateTemplate t = loadDefault(id);
        EstimateTemplateItem item = loadItemOf(id, itemId);
        item.setName(req.name().trim());
        item.setType(req.type());
        item.setUnit(req.unit());
        log.info("admin {} updated item {} of default estimate template {}", actor, itemId, id);
        return EstimateTemplateDetail.from(t, itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id));
    }

    @Transactional
    public EstimateTemplateDetail removeItem(UUID id, UUID itemId, String actor) {
        EstimateTemplate t = loadDefault(id);
        EstimateTemplateItem item = loadItemOf(id, itemId);
        itemRepository.delete(item);
        log.info("admin {} removed item {} from default estimate template {}", actor, itemId, id);
        return EstimateTemplateDetail.from(t, itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id));
    }

    // ---- helpers -----------------------------------------------------------

    /** A default template only — a master's own template is not the admin's to edit. */
    private EstimateTemplate loadDefault(UUID id) {
        EstimateTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate template not found: " + id));
        if (!t.isDefault()) {
            throw new ResourceNotFoundException("Not a default estimate template: " + id);
        }
        return t;
    }

    private EstimateTemplateItem loadItemOf(UUID templateId, UUID itemId) {
        EstimateTemplateItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Template item not found: " + itemId));
        if (!item.getTemplate().getId().equals(templateId)) {
            throw new ResourceNotFoundException("Template item not found in template " + templateId);
        }
        return item;
    }

    private Map<UUID, Long> itemCounts(List<EstimateTemplate> templates) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = templates.stream().map(EstimateTemplate::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : itemRepository.countByTemplateIds(ids)) {
            counts.put(row.getTemplateId(), row.getCnt());
        }
        return counts;
    }
}
