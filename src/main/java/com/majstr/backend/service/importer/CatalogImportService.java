package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportCommitRequest;
import com.majstr.backend.dto.CatalogImportCommitRequest.CommitItem;
import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persists a master-confirmed price-list import into their OWN catalog, in one
 * transaction. Dedup is by {@code (owner, lower(trim(name)), type)} computed in Java
 * over the owner's existing items — no risky {@code lower()} SQL. A duplicate is
 * either price-updated or skipped per the item's (or the batch's) policy; a new
 * position is created with the chosen trade (null → OTHER). Owner-scoped throughout —
 * positions only ever land in the caller's catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogImportService {

    private final CatalogItemRepository catalogRepository;
    private final UserRepository userRepository;

    @Transactional
    public CatalogImportCommitResponse commit(UUID ownerId, CatalogImportCommitRequest req) {
        Trade trade = req.trade() != null ? req.trade() : Trade.OTHER;
        DedupPolicy defaultPolicy = req.defaultPolicy();

        // Existing catalog keyed by (lower(trim(name)) | type) — the dedup index.
        Map<String, CatalogItem> existing = new HashMap<>();
        for (CatalogItem item : catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            existing.putIfAbsent(key(item.getName(), item.getType().name()), item);
        }

        User owner = userRepository.getReferenceById(ownerId);
        int created = 0, updated = 0, skipped = 0;
        for (CommitItem in : req.items()) {
            CatalogItem match = existing.get(key(in.name(), in.type().name()));
            if (match != null) {
                DedupPolicy policy = in.policy() != null ? in.policy() : defaultPolicy;
                if (policy == DedupPolicy.UPDATE_PRICE) {
                    match.setDefaultPrice(in.price());
                    updated++;
                } else {
                    skipped++;
                }
                continue;
            }
            CatalogItem fresh = CatalogItem.builder()
                    .owner(owner)
                    .name(in.name().trim())
                    .type(in.type())
                    .unit(in.unit())
                    .defaultPrice(in.price())
                    .trade(trade)
                    .source(CatalogItemSource.IMPORT)
                    .build();
            catalogRepository.save(fresh);
            // A newly created item can be a dup target for a later row with the same name.
            existing.put(key(fresh.getName(), fresh.getType().name()), fresh);
            created++;
        }
        log.info("Catalog import for {}: created={}, updated={}, skipped={} (trade {})",
                ownerId, created, updated, skipped, trade);
        return new CatalogImportCommitResponse(created, updated, skipped);
    }

    private static String key(String name, String type) {
        return name.trim().toLowerCase(Locale.ROOT) + "|" + type;
    }
}
