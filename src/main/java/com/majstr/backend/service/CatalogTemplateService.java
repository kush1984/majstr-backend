package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Copies starter templates into a user's own catalog. Called from
 * {@code AuthService.register} (so nobody opens an empty library) and
 * from the {@code /api/catalog/reset-from-template} endpoint.
 *
 * <p>Templates for all of the user's trades are merged. Skips any item
 * whose name+type+unit already exists in the user's catalog, and never
 * creates the same item twice when two trades share a position, so the
 * result is duplicate-free and a reset is idempotent (never overwrites
 * the user's own pricing).</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogTemplateService {

    private final CatalogTemplateRepository templateRepository;
    private final CatalogItemRepository catalogRepository;

    @Transactional
    public int seedForUser(User user) {
        return copyMissing(user, templateRepository.findByTradeIn(user.getTrades()));
    }

    @Transactional
    public int resetForUser(User user) {
        return copyMissing(user, templateRepository.findByTradeIn(user.getTrades()));
    }

    private int copyMissing(User owner, List<CatalogTemplate> templates) {
        if (templates.isEmpty()) {
            return 0;
        }
        // Seed the seen-set with what the user already has, then let it grow
        // as we go — this skips both existing items and cross-trade duplicates
        // (a position shared by two of the user's trades is created once).
        Set<String> seen = catalogRepository.findByOwnerIdOrderByNameAsc(owner.getId())
                .stream()
                .map(CatalogTemplateService::key)
                .collect(Collectors.toCollection(HashSet::new));

        List<CatalogItem> toCreate = new ArrayList<>();
        for (CatalogTemplate t : templates) {
            if (seen.add(key(t))) {
                toCreate.add(CatalogItem.builder()
                        .owner(owner)
                        .name(t.getName())
                        .category(t.getCategory())
                        .type(t.getType())
                        .unit(t.getUnit())
                        .defaultPrice(t.getSuggestedPrice())
                        .build());
            }
        }
        catalogRepository.saveAll(toCreate);
        return toCreate.size();
    }

    /** Composite dedup key — same name + type + unit are treated as duplicate. */
    private static String key(CatalogItem item) {
        return item.getName().toLowerCase() + "|" + item.getType() + "|" + item.getUnit();
    }

    private static String key(CatalogTemplate template) {
        return template.getName().toLowerCase() + "|" + template.getType() + "|" + template.getUnit();
    }
}
