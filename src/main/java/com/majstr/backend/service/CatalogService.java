package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    // "Без категорії" (null) sorts last; otherwise case-insensitive by
    // category, then name — a flat list the client can group by category.
    private static final Comparator<CatalogItem> BY_CATEGORY_THEN_NAME =
            Comparator.comparing(CatalogItem::getCategory,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(CatalogItem::getName, String.CASE_INSENSITIVE_ORDER);

    private final CatalogItemRepository catalogRepository;
    private final UserRepository userRepository;

    @Transactional
    public CatalogItemResponse create(CatalogItemRequest req, UUID ownerId) {
        User owner = userRepository.getReferenceById(ownerId);
        CatalogItem item = CatalogItem.builder()
                .owner(owner)
                .name(req.name().trim())
                .category(normalizeCategory(req.category()))
                .trade(req.trade())
                .type(req.type())
                .unit(req.unit())
                .defaultPrice(req.defaultPrice())
                .build();
        return CatalogItemResponse.from(catalogRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<CatalogItemResponse> listForOwner(UUID ownerId, ItemType type) {
        List<CatalogItem> items = type == null
                ? catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)
                : catalogRepository.findByOwnerIdAndTypeOrderByNameAsc(ownerId, type);
        return items.stream()
                .sorted(BY_CATEGORY_THEN_NAME)
                .map(CatalogItemResponse::from)
                .toList();
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
        item.setName(req.name().trim());
        item.setCategory(normalizeCategory(req.category()));
        item.setTrade(req.trade());
        item.setType(req.type());
        item.setUnit(req.unit());
        item.setDefaultPrice(req.defaultPrice());
        return CatalogItemResponse.from(item);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        CatalogItem item = loadOwned(id, ownerId);
        catalogRepository.delete(item);
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
