package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Copies starter templates into a user's own catalog. Called from
 * {@code AuthService.register} (so nobody opens an empty library) and
 * from the {@code /api/catalog/reset-from-template} endpoint.
 *
 * <p>Skips templates whose name already exists in the user's catalog so
 * a reset is idempotent and never overwrites the user's own pricing.</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogTemplateService {

    private final CatalogTemplateRepository templateRepository;
    private final CatalogItemRepository catalogRepository;

    @Transactional
    public int seedForUser(User user) {
        return copyMissing(user, templateRepository.findByTrade(user.getTrade()));
    }

    @Transactional
    public int resetForUser(User user) {
        return copyMissing(user, templateRepository.findByTrade(user.getTrade()));
    }

    private int copyMissing(User owner, List<CatalogTemplate> templates) {
        if (templates.isEmpty()) {
            return 0;
        }
        Set<String> existingKeys = catalogRepository.findByOwnerIdOrderByNameAsc(owner.getId())
                .stream()
                .map(CatalogTemplateService::key)
                .collect(Collectors.toSet());

        List<CatalogItem> toCreate = templates.stream()
                .filter(t -> !existingKeys.contains(key(t)))
                .map(t -> CatalogItem.builder()
                        .owner(owner)
                        .name(t.getName())
                        .type(t.getType())
                        .unit(t.getUnit())
                        .defaultPrice(t.getSuggestedPrice())
                        .build())
                .toList();

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

    public int countForTrade(Trade trade) {
        return templateRepository.findByTrade(trade).size();
    }
}
