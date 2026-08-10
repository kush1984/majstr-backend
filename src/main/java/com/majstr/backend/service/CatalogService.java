package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.CatalogItemsOrderRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogService {

    /**
     * The master's own arrangement (V87), with the id as a tie-break so the order is total and a
     * list never comes back shuffled between two identical reads.
     *
     * <p>This replaced sorting by category-then-name. Alphabetical order is the right DEFAULT — and
     * it is exactly what V87 backfilled into {@code sort_order}, so nothing moved on the first read
     * after the migration — but it stops being the truth the moment he drags something. A derived
     * sort key cannot express «спочатку те, що я роблю щодня».</p>
     */
    private static final Comparator<CatalogItem> BY_MASTERS_ORDER =
            Comparator.comparingInt(CatalogItem::getSortOrder)
                    .thenComparing(CatalogItem::getId);

    private final CatalogItemRepository catalogRepository;
    private final CatalogTemplateRepository catalogTemplateRepository;
    private final UserRepository userRepository;
    private final UserTradeRepository userTradeRepository;

    @Transactional
    public CatalogItemResponse create(CatalogItemRequest req, UUID ownerId) {
        return create(req, ownerId, null);
    }

    /**
     * Create a catalog position, optionally with a CLIENT-PROVIDED id (offline authoring). A
     * replayed offline create is already safe thanks to the (name, type, unit) dedupe below, but
     * honouring the id keeps the entity's identity STABLE — the row the master sees offline keeps
     * the same id after syncing, instead of being swapped for a server-generated one.
     */
    @Transactional
    public CatalogItemResponse create(CatalogItemRequest req, UUID ownerId, UUID requestedId) {
        if (requestedId != null) {
            var byId = catalogRepository.findById(requestedId);
            if (byId.isPresent()) {
                CatalogItem existingById = byId.get();
                if (!existingById.getOwner().getId().equals(ownerId)) {
                    throw new AccessDeniedException("Catalog item does not belong to the current user");
                }
                return CatalogItemResponse.from(existingById); // idempotent replay
            }
        }
        String name = req.name().trim();
        UserTrade customTrade = resolveCustomTrade(req.customTradeId(), ownerId);
        // No specific trade → the single "Інше" catch-all (never null, V33). A custom trade always
        // files as OTHER — trade/customTradeId keep the invariant the CHECK constraint also pins.
        Trade trade = customTrade != null ? Trade.OTHER
                : req.trade() != null ? req.trade() : Trade.OTHER;
        // The catalog is a reference library: one item per (name, type, unit) per owner
        // (enforced by a unique index). Adding an item that already exists UPDATES it
        // instead of creating a duplicate — idempotent, and it can never hit the constraint.
        CatalogItem existing = catalogRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .filter(i -> i.getType() == req.type()
                        && i.getUnit() == req.unit()
                        && i.getName().trim().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setCategory(normalizeCategory(req.category()));
            existing.setTrade(trade);
            existing.setCustomTrade(customTrade);
            existing.setDefaultPrice(req.defaultPrice());
            return CatalogItemResponse.from(existing); // managed entity → dirty-checked
        }
        CatalogItem item = CatalogItem.builder()
                .id(requestedId)
                .owner(userRepository.getReferenceById(ownerId))
                .name(name)
                .category(normalizeCategory(req.category()))
                .trade(trade)
                .customTrade(customTrade)
                .type(req.type())
                .unit(req.unit())
                .defaultPrice(req.defaultPrice())
                // The master typed this themselves — the only rows the admin insight screens
                // treat as evidence about what our defaults are missing.
                .source(CatalogItemSource.MANUAL)
                // At the END of his catalog, never inserted alphabetically. Once he arranges the
                // list himself, dropping a new position into the middle by name would move it away
                // from where he was looking when he added it. The client groups by category, so a
                // position whose category already exists still appears inside that group.
                .sortOrder(catalogRepository.nextSortOrder(ownerId))
                .build();
        return CatalogItemResponse.from(catalogRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<CatalogItemResponse> listForOwner(UUID ownerId, ItemType type) {
        List<CatalogItem> items = type == null
                ? catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)
                : catalogRepository.findByOwnerIdAndTypeOrderByNameAsc(ownerId, type);
        Map<String, List<Trade>> sharedByKey = sharedTradesByNameKey();
        return items.stream()
                .sorted(BY_MASTERS_ORDER)
                .map(item -> CatalogItemResponse.from(item, sharedTradesFor(item, sharedByKey)))
                .toList();
    }

    /** {@code "name_key|TYPE|UNIT" -> every trade catalog_templates ships it under}, only for
     *  keys more than one trade recognizes — see
     *  {@link CatalogTemplateRepository#findNameKeysSharedAcrossTrades}. */
    private Map<String, List<Trade>> sharedTradesByNameKey() {
        Map<String, List<Trade>> map = new HashMap<>();
        for (Object[] row : catalogTemplateRepository.findNameKeysSharedAcrossTrades()) {
            String key = row[0] + "|" + row[1] + "|" + row[2];
            List<Trade> trades = Arrays.stream(((String) row[3]).split(","))
                    .map(Trade::valueOf)
                    .toList();
            map.put(key, trades);
        }
        return map;
    }

    private static List<Trade> sharedTradesFor(CatalogItem item, Map<String, List<Trade>> sharedByKey) {
        String key = item.getName().trim().toLowerCase(Locale.ROOT)
                + "|" + item.getType() + "|" + item.getUnit();
        List<Trade> all = sharedByKey.get(key);
        if (all == null) {
            return List.of();
        }
        return all.stream().filter(t -> t != item.getTrade()).toList();
    }

    /** Distinct categories the contractor has used — for the picker/autocomplete. */
    @Transactional(readOnly = true)
    public List<String> categories(UUID ownerId) {
        return catalogRepository.findDistinctCategoriesByOwner(ownerId);
    }

    private static final int MAX_SEARCH_RESULTS = 20;

    /**
     * Autocomplete: case-insensitive partial-name search over the owner's own
     * catalog, optionally filtered by type, capped at {@code limit} (1..20).
     * A blank query returns an empty list (don't dump the whole catalog into a
     * suggestion dropdown). Exact-prefix matches come first, then alphabetical.
     */
    @Transactional(readOnly = true)
    public List<CatalogItemResponse> search(UUID ownerId, String query, ItemType type, int limit) {
        String pattern = likePattern(query);
        if (pattern == null) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        return catalogRepository
                .searchByOwner(ownerId, type, pattern, prefixPattern(query), PageRequest.of(0, safeLimit))
                .stream()
                .map(CatalogItemResponse::from)
                .toList();
    }

    /**
     * Case-insensitive {@code %term%} LIKE pattern, or {@code null} for a blank
     * query. Built in Java so the bind parameter is a plain text LIKE operand —
     * never wrapped in {@code LOWER(CONCAT(...))}, which made PostgreSQL infer
     * {@code bytea} and fail with "function lower(bytea) does not exist" (Fix K).
     */
    static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** {@code term%} pattern used to rank exact-prefix matches first. */
    static String prefixPattern(String query) {
        return query.trim().toLowerCase(Locale.ROOT) + "%";
    }

    @Transactional
    public CatalogItemResponse update(UUID id, CatalogItemRequest req, UUID ownerId) {
        CatalogItem item = loadOwned(id, ownerId);
        UserTrade customTrade = resolveCustomTrade(req.customTradeId(), ownerId);
        item.setName(req.name().trim());
        item.setCategory(normalizeCategory(req.category()));
        item.setTrade(customTrade != null ? Trade.OTHER
                : req.trade() != null ? req.trade() : Trade.OTHER);
        item.setCustomTrade(customTrade);
        item.setType(req.type());
        item.setUnit(req.unit());
        item.setDefaultPrice(req.defaultPrice());
        return CatalogItemResponse.from(item);
    }

    /** Resolve and ownership-check a custom trade id from a request; {@code null} passes through. */
    private UserTrade resolveCustomTrade(UUID customTradeId, UUID ownerId) {
        if (customTradeId == null) {
            return null;
        }
        return userTradeRepository.findByIdAndUserId(customTradeId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Custom trade not found: " + customTradeId));
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        // Idempotent: a replayed offline delete of an already-gone item is a no-op, not a 404.
        catalogRepository.findById(id).ifPresent(item -> {
            if (!item.getOwner().getId().equals(ownerId)) {
                throw new AccessDeniedException("Catalog item does not belong to the current user");
            }
            catalogRepository.delete(item);
        });
    }

    /**
     * Delete many positions at once — the master clearing out a trade he does not do.
     *
     * <p>Scoped to the owner in the query itself, so an id belonging to someone else matches
     * nothing rather than throwing: this arrives from a screen where the master ticked rows he can
     * see, and a partial failure halfway through a list of 200 would leave him guessing what
     * survived. Returns how many actually went, which is what the client reports back to him.</p>
     *
     * <p>Idempotent for the same reason single delete is — the offline outbox replays it.</p>
     *
     * <p><b>Estimates are untouched by design.</b> An estimate line holds its own name, unit and
     * price with no FK to the catalog, so deleting a position never rewrites work already quoted.
     * Templates reference positions BY NAME and look the price up here, so a template applied after
     * this comes back with a zero price for what is gone — which is why the screen warns before
     * a large deletion rather than after.</p>
     */
    @Transactional
    public int deleteItems(List<UUID> ids, UUID ownerId) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return catalogRepository.deleteByOwnerIdAndIdIn(ownerId, ids);
    }

    /**
     * Persist the arrangement the master dragged into place.
     *
     * <p>Mirrors {@code EstimateService.reorderItems} line for line, including the part that is
     * easy to miss: anything the request never mentioned keeps its relative order AFTER the rest.
     * The client can legitimately send a subset — a stale list, or a position created on another
     * device since — and dropping those would silently delete them from the order.</p>
     */
    @Transactional
    public List<CatalogItemResponse> reorderItems(CatalogItemsOrderRequest req, UUID ownerId) {
        List<CatalogItem> existing = catalogRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId);
        Map<UUID, CatalogItem> byId = existing.stream()
                .collect(Collectors.toMap(CatalogItem::getId, i -> i));

        int position = 0;
        Set<UUID> placed = new LinkedHashSet<>();
        for (CatalogItemsOrderRequest.Line line : req.items()) {
            CatalogItem item = byId.get(line.id());
            if (item == null || !placed.add(line.id())) {
                continue;   // gone since the arrangement was captured, or listed twice
            }
            item.setSortOrder(position++);
            item.setCategory(normalizeCategory(line.category()));
        }
        for (CatalogItem item : existing) {
            if (!placed.contains(item.getId())) {
                item.setSortOrder(position++);
            }
        }
        return catalogRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId).stream()
                .map(CatalogItemResponse::from)
                .toList();
    }

    CatalogItem loadOwned(UUID id, UUID ownerId) {
        CatalogItem item = catalogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog item not found: " + id));
        if (!item.getOwner().getId().equals(ownerId)) {
            throw new AccessDeniedException("Catalog item does not belong to the current user");
        }
        return item;
    }

    /** Trim, collapse internal whitespace, and treat blank as "no category". */
    static String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }
}
