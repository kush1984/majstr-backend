package com.majstr.backend.service;

import com.majstr.backend.dto.AdminCatalogTemplatePage;
import com.majstr.backend.dto.AdminCatalogTemplateRequest;
import com.majstr.backend.dto.AdminCatalogTemplateResponse;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Admin editing of the shared default catalog ({@code catalog_templates}). CRUD
 * only — {@code ROLE_ADMIN} is enforced by the security chain on
 * {@code /api/admin/**}. Nothing caches these rows, so every change is live for
 * the app without a restart.
 *
 * <p>How a change reaches masters:</p>
 * <ul>
 *   <li><b>Create</b> stamps the new position at {@code currentVersion() + 1}, so
 *       every existing master picks it up under "Add new from library" (the version
 *       cutoff in {@link CatalogTemplateService#addNewFromCatalog}) and fresh
 *       registrations get it seeded.</li>
 *   <li><b>Update / delete</b> affect only NEW registrations (which seed live from
 *       this table). A master who already copied the item keeps their own copy —
 *       their {@code catalog_items} are never touched (their pricing is theirs).
 *       Pushing edits into already-owned items is a separate, opt-in feature
 *       (docs/open-questions "Market-price updates for existing catalog items").</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminCatalogTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AdminCatalogTemplateService.class);

    private final CatalogTemplateRepository repository;

    @Transactional(readOnly = true)
    public AdminCatalogTemplatePage list(Trade trade, ItemType type, String q, int page, int size) {
        Page<CatalogTemplate> found = repository.adminSearch(trade, type, q, PageRequest.of(page, size));
        return new AdminCatalogTemplatePage(
                found.getContent().stream().map(AdminCatalogTemplateResponse::from).toList(),
                found.getNumber(),
                found.getTotalPages(),
                found.getTotalElements(),
                repository.currentVersion());
    }

    @Transactional
    public AdminCatalogTemplateResponse create(AdminCatalogTemplateRequest req, String actor) {
        // Next version → existing masters see it under "Add new from library".
        int version = repository.currentVersion() + 1;
        CatalogTemplate t = repository.save(CatalogTemplate.builder()
                .trade(req.trade())
                .category(normalize(req.category()))
                .name(req.name().trim())
                .type(req.type())
                .unit(req.unit())
                .suggestedPrice(req.suggestedPrice())
                .addedInVersion(version)
                .build());
        log.info("admin {} created catalog template {} '{}' ({}) at version {}",
                actor, t.getId(), t.getName(), t.getTrade(), version);
        return AdminCatalogTemplateResponse.from(t);
    }

    @Transactional
    public AdminCatalogTemplateResponse update(UUID id, AdminCatalogTemplateRequest req, String actor) {
        CatalogTemplate t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog template not found: " + id));
        t.setTrade(req.trade());
        t.setCategory(normalize(req.category()));
        t.setName(req.name().trim());
        t.setType(req.type());
        t.setUnit(req.unit());
        t.setSuggestedPrice(req.suggestedPrice());
        // addedInVersion left as-is on purpose: an edit must not re-propagate to
        // masters who already copied the item (their catalog is sacred).
        log.info("admin {} updated catalog template {} '{}'", actor, id, t.getName());
        return AdminCatalogTemplateResponse.from(t);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        CatalogTemplate t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog template not found: " + id));
        repository.delete(t);
        log.info("admin {} deleted catalog template {} '{}'", actor, id, t.getName());
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
