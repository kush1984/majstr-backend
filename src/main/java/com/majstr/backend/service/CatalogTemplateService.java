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
        int added = copyMissing(user, templateRepository.findByTradeIn(user.getTrades()));
        user.setLastSyncedCatalogVersion(templateRepository.currentVersion());
        return added;
    }

    @Transactional
    public int resetForUser(User user) {
        int added = copyMissing(user, templateRepository.findByTradeIn(user.getTrades()));
        user.setLastSyncedCatalogVersion(templateRepository.currentVersion());
        return added;
    }

    /**
     * "Add new from catalog" — pulls only templates added in a version NEWER than
     * the user last synced, for the user's trades, that aren't already in their
     * catalog. Never re-adds what they deleted/renamed in older versions (the
     * version cutoff guarantees it), never overwrites prices. Advances the user's
     * synced version to current even if nothing was added. The {@code user} must
     * be loaded with trades eager-fetched.
     */
    @Transactional
    public int addNewFromCatalog(User user) {
        List<CatalogTemplate> newer = templateRepository.findByTradeInAndAddedInVersionGreaterThan(
                user.getTrades(), user.getLastSyncedCatalogVersion());
        int added = copyMissing(user, newer);
        user.setLastSyncedCatalogVersion(templateRepository.currentVersion());
        return added;
    }

    /** How many NEW catalog items the "Add new" button would add (for the preview
     *  "Знайдено N нових позицій") — newer-version, trade-matched, not duplicates. */
    @Transactional(readOnly = true)
    public int countNewFromCatalog(User user) {
        List<CatalogTemplate> newer = templateRepository.findByTradeInAndAddedInVersionGreaterThan(
                user.getTrades(), user.getLastSyncedCatalogVersion());
        return missingItems(user, newer).size();
    }

    /**
     * Merges the starter set of specific trades into the user's catalog — used
     * when a trade is added to the profile. Only the user's OWN trades are
     * honoured (never seed a set they don't have). This is a <b>merge</b>, not a
     * reset: {@code copyMissing} adds only template items the user doesn't
     * already have (by name+type+unit) and never overwrites or deletes their
     * own items. The {@code user} must be loaded with trades eager-fetched
     * (the caller uses {@code findWithTradesById}) so {@code getTrades()} is safe.
     */
    @Transactional
    public int addTemplatesForTrades(User user, Set<Trade> requestedTrades) {
        Set<Trade> trades = requestedTrades.stream()
                .filter(user.getTrades()::contains)
                .collect(Collectors.toCollection(HashSet::new));
        if (trades.isEmpty()) {
            return 0;
        }
        return copyMissing(user, templateRepository.findByTradeIn(trades));
    }

    private int copyMissing(User owner, List<CatalogTemplate> templates) {
        List<CatalogItem> toCreate = missingItems(owner, templates);
        if (toCreate.isEmpty()) {
            return 0;
        }
        catalogRepository.saveAll(toCreate);
        return toCreate.size();
    }

    /**
     * The CatalogItems that would be created for {@code owner} from {@code templates}
     * — every template not already in the catalog by name+type+unit, de-duplicated
     * across trades too. Never overwrites existing items (the copy is read-only for
     * the catalog). Pure (no writes), so it also backs the "how many new?" preview.
     */
    private List<CatalogItem> missingItems(User owner, List<CatalogTemplate> templates) {
        if (templates.isEmpty()) {
            return List.of();
        }
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
                        .trade(t.getTrade()) // carry the trade so the catalog can filter by it
                        .type(t.getType())
                        .unit(t.getUnit())
                        .defaultPrice(t.getSuggestedPrice())
                        .build());
            }
        }
        return toCreate;
    }

    /** Composite dedup key — same name + type + unit are treated as duplicate. */
    private static String key(CatalogItem item) {
        return item.getName().toLowerCase() + "|" + item.getType() + "|" + item.getUnit();
    }

    private static String key(CatalogTemplate template) {
        return template.getName().toLowerCase() + "|" + template.getType() + "|" + template.getUnit();
    }
}
