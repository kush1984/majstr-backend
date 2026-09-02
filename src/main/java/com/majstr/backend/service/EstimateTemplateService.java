package com.majstr.backend.service;

import com.majstr.backend.dto.ApplyTemplatesRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.dto.TemplateItemsOrderRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.TemplateDefaultOverride;
import com.majstr.backend.entity.TemplateTradeOverride;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.TemplateDefaultOverrideRepository;
import com.majstr.backend.repository.TemplateTradeOverrideRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Column cap on {@code estimates.quality_note} (V121). */
    private static final int QUALITY_NOTE_MAX = 1000;

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
    private final TemplateDefaultOverrideRepository defaultOverrideRepository;
    private final UserTradeRepository userTradeRepository;
    private final UserRepository userRepository;

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
        // Defaults this master deleted, or edited into their own copy — either way out of the list.
        Set<UUID> retired = retiredDefaults(user.getId());
        List<EstimateTemplate> own = templateRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());

        List<EstimateTemplate> all = new ArrayList<>(
                defaults.stream().filter(t -> !retired.contains(t.getId())).toList());
        all.addAll(own);
        Map<UUID, Long> counts = itemCounts(all);

        return all.stream()
                .map(t -> {
                    UserTrade customTrade = t.getCustomTrade();
                    return new EstimateTemplateSummary(
                            t.getId(), t.getName(), t.getDescription(), effectiveTrade(t, overrides),
                            customTrade != null ? customTrade.getId() : null,
                            customTrade != null ? customTrade.getName() : null,
                            t.isDefault(),
                            counts.getOrDefault(t.getId(), 0L).intValue());
                })
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

    /** System defaults this master has hidden or forked — ids only. */
    private Set<UUID> retiredDefaults(UUID userId) {
        return defaultOverrideRepository.findByUserId(userId).stream()
                .map(TemplateDefaultOverride::getTemplateId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The master's own filing wins over the template's shipped trade. */
    private static Trade effectiveTrade(EstimateTemplate t, Map<UUID, Optional<Trade>> overrides) {
        Optional<Trade> override = overrides.get(t.getId());
        return override != null ? override.orElse(null) : t.getTrade();
    }

    /**
     * Re-file a template into a trade. An OWN template carries the trade on its own row (and may
     * be filed under a master-invented trade — {@code customTradeId}); a system default is shared,
     * so the change is stored as this master's own override and stays invisible to everyone else.
     * {@code customTradeId} only makes sense for an own template — {@link TemplateTradeOverride}
     * has no column for it by design, so it is silently ignored when re-filing a default.
     */
    @Transactional
    public EstimateTemplateSummary setTrade(UUID templateId, Trade trade, UUID customTradeId, UUID ownerId) {
        EstimateTemplate template = loadAccessible(templateId, ownerId);
        UserTrade customTrade = null;
        Trade effectiveTrade = trade;
        if (!template.isDefault()) {
            customTrade = resolveCustomTrade(customTradeId, ownerId);
            effectiveTrade = customTrade != null ? Trade.OTHER : trade;
            template.setTrade(effectiveTrade);
            template.setCustomTrade(customTrade);
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
                template.getId(), template.getName(), template.getDescription(), effectiveTrade,
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                template.isDefault(), count);
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
     * object-agnostic). {@code trade} files it under a trade (null = general);
     * {@code customTradeId} (a master-invented trade) wins over it, same as a catalog item.
     */
    @Transactional
    public EstimateTemplateSummary saveFromEstimate(UUID estimateId, String name, String description,
                                                    Trade trade, UUID customTradeId, UUID ownerId) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        User owner = estimate.getProject().getOwner();
        UserTrade customTrade = resolveCustomTrade(customTradeId, ownerId);
        Trade effectiveTrade = customTrade != null ? Trade.OTHER : trade;
        List<EstimateItem> items = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
        EstimateTemplate template = templateRepository.save(EstimateTemplate.builder()
                .owner(owner)
                .name(name.trim())
                .description(normalize(description))
                .trade(effectiveTrade)
                .customTrade(customTrade)
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
                template.getId(), template.getName(), template.getDescription(), effectiveTrade,
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                false, toSave.size());
    }

    /**
     * Update a template's metadata — its name and its client-facing description. On a system
     * default this forks it first, so the summary carries the copy's id.
     *
     * <p>{@code description} is <b>null = leave it as it is</b>, blank = clear it. The request is
     * otherwise a full replace, and a plain rename (from the estimate editor, or replayed from the
     * offline outbox) carries no description — reading null as «clear» would silently drop the
     * paragraph the client reads under the table.</p>
     */
    @Transactional
    public EstimateTemplateSummary updateMeta(UUID templateId, String name, String description,
                                              UUID ownerId) {
        EstimateTemplate template = loadWritable(templateId, ownerId);
        template.setName(name.trim());
        if (description != null) {
            template.setDescription(normalize(description));
        }
        long count = templateItemRepository
                .findByTemplateIdOrderBySortOrderAscIdAsc(template.getId()).size();
        UserTrade customTrade = template.getCustomTrade();
        return new EstimateTemplateSummary(
                template.getId(), template.getName(), template.getDescription(), template.getTrade(),
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                false, (int) count);
    }

    /** Resolve and ownership-check a custom trade id from a request; {@code null} passes through. */
    private UserTrade resolveCustomTrade(UUID customTradeId, UUID ownerId) {
        if (customTradeId == null) {
            return null;
        }
        return userTradeRepository.findByIdAndUserId(customTradeId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Custom trade not found: " + customTradeId));
    }

    /**
     * Delete a template. An OWN one is really deleted; a SYSTEM DEFAULT is shared by every master,
     * so it is <b>hidden for this master only</b> — «видалити шаблон» has to work on the bundles a
     * master actually sees, and 87 of the 88 of those are defaults. {@link #restoreDefaults} brings
     * them back.
     */
    @Transactional
    public void delete(UUID templateId, UUID ownerId) {
        // Idempotent: a replayed offline delete of an already-gone template is a no-op, not a 404.
        templateRepository.findById(templateId).ifPresent(t -> {
            if (t.isDefault()) {
                hideDefault(templateId, ownerId, null);
                return;
            }
            loadOwnTemplate(templateId, ownerId); // ownership check
            templateRepository.delete(t); // items cascade (FK ON DELETE CASCADE)
        });
    }

    /** Un-hide every system default this master retired. Their own copies are left alone. */
    @Transactional
    public void restoreDefaults(UUID ownerId) {
        defaultOverrideRepository.deleteByUserId(ownerId);
    }

    /** Add a position (appended last). On a system default this forks it first. */
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
        EstimateTemplate template = loadWritable(templateId, ownerId);
        List<EstimateTemplateItem> items =
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(template.getId());
        if (requestedId != null) {
            var existing = templateItemRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getTemplate().getId().equals(template.getId())) {
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

    /** Remove a position. On a system default this forks it first. */
    @Transactional
    public EstimateTemplateDetail removeItem(UUID templateId, UUID itemId, UUID ownerId) {
        EstimateTemplate template = loadWritable(templateId, ownerId);
        // Idempotent: a replayed offline removal of an already-gone position is a no-op, not a 404.
        templateItemRepository.findById(itemId)
                .filter(i -> i.getTemplate().getId().equals(template.getId()))
                .ifPresent(templateItemRepository::delete);
        return detailOf(template);
    }

    /**
     * Edit a position in place — the same name/type/unit the master picks from the catalog or
     * types by hand when adding one. On a system default this forks it first.
     *
     * <p>Idempotent for an offline replay: it is a full statement of the position, not a delta,
     * and an item that no longer exists is a no-op rather than a 404 (mirrors {@link #removeItem}).
     * </p>
     */
    @Transactional
    public EstimateTemplateDetail updateItem(UUID templateId, UUID itemId,
                                             TemplateItemRequest req, UUID ownerId) {
        EstimateTemplate template = loadWritable(templateId, ownerId);
        templateItemRepository.findById(itemId)
                .filter(i -> i.getTemplate().getId().equals(template.getId()))
                .ifPresent(item -> {
                    item.setName(req.name().trim());
                    item.setType(req.type());
                    item.setUnit(req.unit());
                });
        return detailOf(template);
    }

    /**
     * Rearrange a template's positions. A bundle is a SEQUENCE — what is done after what — so the
     * order the master drags them into IS the content (docs/architecture.md → "A default bundle is
     * a SEQUENCE, not a set"). On a system default this forks it first.
     *
     * <p>Declarative and therefore replay-safe, exactly like {@code EstimateService.reorderItems}:
     * unknown ids are ignored and positions the request omits keep their relative order after the
     * ones it names, so a stale client can never drop a line out of the bundle.</p>
     */
    @Transactional
    public EstimateTemplateDetail reorderItems(UUID templateId, TemplateItemsOrderRequest req, UUID ownerId) {
        EstimateTemplate template = loadWritable(templateId, ownerId);
        List<EstimateTemplateItem> existing =
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(template.getId());
        Map<UUID, EstimateTemplateItem> byId = existing.stream()
                .collect(Collectors.toMap(EstimateTemplateItem::getId, i -> i));

        int position = 0;
        Set<UUID> placed = new LinkedHashSet<>();
        for (UUID id : req.itemIds()) {
            EstimateTemplateItem item = byId.get(id);
            if (item == null || !placed.add(id)) {
                continue;
            }
            item.setSortOrder(position++);
        }
        for (EstimateTemplateItem item : existing) {
            if (!placed.contains(item.getId())) {
                item.setSortOrder(position++);
            }
        }
        templateItemRepository.saveAll(existing);
        return detailOf(template);
    }

    private EstimateTemplateDetail detailOf(EstimateTemplate template) {
        return EstimateTemplateDetail.from(
                template,
                templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(template.getId()));
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
        return applyToProject(projectId, ApplyTemplatesRequest.wholeBundles(List.of(templateId), req), ownerId);
    }

    /**
     * The same, from SEVERAL templates at once. A real job is rarely one bundle — a bathroom is
     * «Санвузол» plus «Підлога плиткою», and before this the master applied one and typed the
     * rest.
     *
     * <p>Positions are concatenated in the order the templates were picked, then <b>de-duplicated
     * by lowercased name</b> — the same key the catalog price lookup below already uses, so a
     * position that resolves to one catalog row can only produce one line. First occurrence wins,
     * which keeps the earliest-picked template's unit and ordering. Without this, overlapping
     * bundles (every tiling bundle carries «Ґрунтівка поверхні») would produce an estimate with
     * the same work billed three times, which the client would see.</p>
     *
     * <p>Every template is ownership-checked individually, and the estimate limit is checked once
     * — this creates ONE estimate however many bundles feed it.</p>
     *
     * <p><b>Only the ticked positions come across.</b> A big bundle is usually applied for five or
     * six of its lines and the rest were thrown out by hand afterwards, one at a time — so the
     * request may name a subset per bundle ({@link ApplyTemplatesRequest#pickedItemIds()}); a
     * bundle that names none contributes all of them. The subset is applied BEFORE the de-dup
     * above, so an untick in the first bundle lets the second one's wording of the same position
     * through instead of dropping the line entirely.</p>
     */
    @Transactional
    public EstimateResponse applyToProject(UUID projectId,
                                           ApplyTemplatesRequest request,
                                           UUID ownerId) {
        List<UUID> templateIds = request.templates() == null ? List.of() : request.templateIds();
        if (templateIds.isEmpty()) {
            throw new ResourceNotFoundException("No estimate template given");
        }
        EstimateCreateRequest req = request.estimateOrEmpty();
        Map<UUID, Set<UUID>> pickedItems = request.pickedItemIds();
        List<EstimateTemplate> templates = templateIds.stream()
                .map(id -> loadAccessible(id, ownerId))
                .toList();
        Project project = projectService.loadOwned(projectId, ownerId);
        limitService.requireCanAddEstimate(ownerId, projectId);

        // Master's catalog by NORMALISED name → price/type/unit at apply-time.
        //
        // One key function for all three uses below (map, dedup, lookup). They used to disagree —
        // the map and the lookup lowercased without trimming while the dedup trimmed — so a name
        // differing by one space matched nothing and the line arrived priced at ZERO, silently.
        // Nothing is broken today (all 167 tiling names align on both sides), but a bundle line and
        // a catalog position are joined BY NAME and nothing enforces that they stay identical.
        Map<String, CatalogItem> catalog = catalogRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .collect(Collectors.toMap(c -> nameKey(c.getName()), c -> c, (a, b) -> a));

        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project)
                .name(normalize(req.name()))
                .validUntil(req.validUntil())
                .notes(normalize(req.notes()))
                .qualityNote(qualityNote(templates))
                .build());

        Set<String> seen = new HashSet<>();
        List<EstimateItem> toSave = new ArrayList<>();
        for (EstimateTemplate template : templates) {
            Set<UUID> picked = pickedItems.get(template.getId());
            for (EstimateTemplateItem ti : templateItemRepository
                    .findByTemplateIdOrderBySortOrderAscIdAsc(template.getId())) {
                if (picked != null && !picked.contains(ti.getId())) {
                    continue; // the master unticked it in the picker
                }
                if (!seen.add(nameKey(ti.getName()))) {
                    continue; // already contributed by an earlier template
                }
                CatalogItem match = catalog.get(nameKey(ti.getName()));
                Unit unit = match != null ? match.getUnit() : ti.getUnit();
                BigDecimal catalogPrice = match != null ? match.getDefaultPrice() : BigDecimal.ZERO;
                // A PERCENT position's catalog "price" IS the percent — see percentQuantity.
                boolean percent = unit == Unit.PERCENT;
                toSave.add(EstimateItem.builder()
                        .estimate(estimate)
                        .type(match != null ? match.getType() : ti.getType())
                        .name(ti.getName())
                        .category(match != null ? match.getCategory() : null)
                        // The explanation rides along with the price it was joined to (V119) — a
                        // bundle carries no description of its own, the catalog position does.
                        .description(match != null ? match.getDescription() : null)
                        .unit(unit)
                        // Ordinary line: empty, the master fills it per object. PERCENT line: the
                        // percent comes from the catalog, because a template carries NO price at
                        // all — leaving it zero would deliver «0 % від …», which is not a надбавка.
                        .quantity(percent ? catalogPrice : BigDecimal.ZERO)
                        .unitPrice(percent ? BigDecimal.ZERO : catalogPrice)
                        .percentBaseKind(percent ? PercentBaseKind.POSITION : null)
                        // Renumbered across the whole result: each template starts its own
                        // sortOrder at 0, so keeping them would interleave the bundles.
                        .sortOrder(toSave.size())
                        .build());
            }
        }
        estimateItemRepository.saveAll(toSave);
        projectRepository.incrementEstimatesCreated(projectId); // lifetime churn counter
        return estimateService.get(estimate.getId(), ownerId);
    }

    /**
     * The client-facing paragraph the applied bundles promised — a SNAPSHOT, never a join (same
     * rule as the line description, V119): the client signed THIS wording, so re-wording the
     * bundle afterwards must not change a signed estimate.
     *
     * <p>Several bundles can feed one estimate, so the described ones are joined in the order they
     * were picked, blank line between. Capped at the column's 1000 characters — the note explains
     * a finish level, it is not a second «Умови».</p>
     */
    private static String qualityNote(List<EstimateTemplate> templates) {
        String joined = templates.stream()
                .map(t -> normalize(t.getDescription()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("\n\n"));
        if (joined.isBlank()) {
            return null;
        }
        return joined.length() <= QUALITY_NOTE_MAX ? joined : joined.substring(0, QUALITY_NOTE_MAX);
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

    /**
     * A template the caller may WRITE to. Their own one is returned as is; a SYSTEM DEFAULT is a
     * shared row nobody may edit, so it is <b>forked on write</b>: copied into the master's own
     * template (name, effective trade, every position in order), the original hidden for them
     * alone, and the copy returned. Every write endpoint answers with the resulting template, id
     * included, so the client simply follows the id it gets back.
     *
     * <p>The fork is recorded in {@code template_default_override}, which makes a second write on
     * the same default land in the SAME copy — without it an offline replay would fork twice and
     * the master would find two half-edited bundles.</p>
     */
    private EstimateTemplate loadWritable(UUID templateId, UUID ownerId) {
        EstimateTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Estimate template not found: " + templateId));
        if (!template.isDefault()) {
            return loadOwnTemplate(templateId, ownerId);
        }
        Optional<TemplateDefaultOverride> row =
                defaultOverrideRepository.findByUserIdAndTemplateId(ownerId, templateId);
        Optional<EstimateTemplate> fork = row
                .map(TemplateDefaultOverride::getForkedTemplateId)
                .flatMap(templateRepository::findById);
        return fork.orElseGet(() -> forkDefault(template, ownerId));
    }

    /** Copy a system default into the caller's own editable template and retire the original. */
    private EstimateTemplate forkDefault(EstimateTemplate original, UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        // The master may have re-filed the default into one of their trades; the copy inherits that
        // filing, or the bundle would jump back to the trade it shipped under the moment it is edited.
        Trade trade = tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, original.getId())
                .map(TemplateTradeOverride::getTrade)
                .orElseGet(original::getTrade);
        EstimateTemplate copy = templateRepository.save(EstimateTemplate.builder()
                .owner(owner)
                .name(original.getName())
                .trade(trade)
                .isDefault(false)
                .build());
        List<EstimateTemplateItem> items = templateItemRepository
                .findByTemplateIdOrderBySortOrderAscIdAsc(original.getId()).stream()
                .map(i -> EstimateTemplateItem.builder()
                        .template(copy)
                        .name(i.getName())
                        .type(i.getType())
                        .unit(i.getUnit())
                        .sortOrder(i.getSortOrder())
                        .build())
                .toList();
        templateItemRepository.saveAll(items);
        // The filing now lives on the copy's own row; the override would point at a hidden default.
        tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, original.getId())
                .ifPresent(tradeOverrideRepository::delete);
        hideDefault(original.getId(), ownerId, copy.getId());
        return copy;
    }

    /** Take a system default out of this master's list; {@code forkId} names the copy, if any. */
    private void hideDefault(UUID templateId, UUID ownerId, UUID forkId) {
        TemplateDefaultOverride row = defaultOverrideRepository
                .findByUserIdAndTemplateId(ownerId, templateId)
                .orElseGet(() -> TemplateDefaultOverride.builder()
                        .userId(ownerId).templateId(templateId).build());
        row.setForkedTemplateId(forkId);
        defaultOverrideRepository.save(row);
    }

    /** The caller's OWN template only — for delete (defaults are handled by hiding instead). */
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
/**
     * The one key a bundle line and a catalog position are matched on.
     *
     * <p>They are joined BY NAME and nothing enforces that the two spellings stay identical, so a
     * name differing by a single space matched nothing — and the line arrived priced at ZERO, with
     * no error anywhere. Three call sites used to disagree about this key: the map and the lookup
     * lowercased without trimming while the dedup trimmed. Collapsing runs of whitespace and the
     * stray space after an opening bracket («( плюс % до м.кв.») makes the join survive the
     * untidiness real seed data has. V88 normalises the stored names too; this is the belt to that
     * migration's braces.</p>
     */
    static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")")
                .trim().toLowerCase();
    }
}

