package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.CatalogUpdateNoticeKind;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.dto.CatalogUpdateNoticeResponse;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.CatalogUpdateNoticeRepository;
import com.majstr.backend.service.catalog.CatalogNameKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private final CatalogUpdateNoticeRepository noticeRepository;

    /**
     * Every pending "we changed your catalog" notice for this master, oldest first — a queue, not
     * a single slot (see {@link com.majstr.backend.entity.CatalogUpdateNotice}). A COUNT notice
     * is written only by a catalog migration; a PRICE_DRIFT notice only by
     * {@code PriceInsightService#applyPriceDrift}. Neither is written here.
     */
    @Transactional(readOnly = true)
    public List<CatalogUpdateNoticeResponse> pendingUpdateNotices(UUID userId) {
        return noticeRepository.findByUserIdAndDismissedAtIsNullOrderByCreatedAtAsc(userId).stream()
                .map(n -> new CatalogUpdateNoticeResponse(n.getId(), n.getKind(),
                        n.getPositionsAdded(), n.getPositionsRemoved(),
                        n.getPositionName(), n.getOldPrice(), n.getNewPrice()))
                .toList();
    }

    /** Idempotent: dismissing an already-dismissed, foreign, or missing id is a no-op, so a retry
     *  from an offline client can never fail. Never touches a master's catalog price — that only
     *  happens through {@link #acceptUpdateNotice}. */
    @Transactional
    public void dismissUpdateNotice(UUID userId, UUID noticeId) {
        noticeRepository.findByIdAndUserId(noticeId, userId)
                .ifPresent(n -> n.setDismissedAt(Instant.now()));
    }

    /**
     * "Прийняти" on a PRICE_DRIFT notice: updates the master's own {@code LIBRARY} catalog item
     * to the new price, but ONLY if it still carries the exact old price from the notice — if the
     * master edited it themselves in the meantime, their number is never touched, the notice is
     * just dismissed. A COUNT notice has no price to accept; this is then the same as dismiss.
     * Idempotent for the same reasons as {@link #dismissUpdateNotice}.
     */
    @Transactional
    public void acceptUpdateNotice(UUID userId, UUID noticeId) {
        noticeRepository.findByIdAndUserId(noticeId, userId).ifPresent(n -> {
            if (n.getKind() == CatalogUpdateNoticeKind.PRICE_DRIFT) {
                String key = CatalogNameKey.of(n.getPositionName());
                catalogRepository.findByOwnerIdOrderByNameAsc(userId).stream()
                        .filter(item -> item.getSource() == CatalogItemSource.LIBRARY)
                        .filter(item -> item.getDefaultPrice().compareTo(n.getOldPrice()) == 0)
                        .filter(item -> key.equals(CatalogNameKey.of(item.getName())))
                        .forEach(item -> item.setDefaultPrice(n.getNewPrice()));
            }
            n.setDismissedAt(Instant.now());
        });
    }

    @Transactional
    public int seedForUser(User user) {
        int added = copyMissing(user, templateRepository.findByTradeInOrderBySortOrderAsc(user.getTrades()));
        user.setLastSyncedCatalogVersion(templateRepository.currentVersion());
        return added;
    }

    @Transactional
    public int resetForUser(User user) {
        int added = copyMissing(user, templateRepository.findByTradeInOrderBySortOrderAsc(user.getTrades()));
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
        return copyMissing(user, templateRepository.findByTradeInOrderBySortOrderAsc(trades));
    }

    private int copyMissing(User owner, List<CatalogTemplate> templates) {
        List<CatalogItem> toCreate = missingItems(owner, templates);
        if (toCreate.isEmpty()) {
            return 0;
        }
        // Numbered from the end of what the master already has, in the library's own order (V118).
        // This used to be left unset, so every row ever copied here landed on the column DEFAULT 0
        // — a whole registration's worth of catalog sharing one slot, which PostgreSQL is free to
        // return in any order it likes. The categories holding those rows then floated to the top
        // of his page, since a section sits where its first row does.
        int position = catalogRepository.nextSortOrder(owner.getId());
        for (CatalogItem item : toCreate) {
            item.setSortOrder(position++);
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
                        // carry the trade so the catalog can filter by it; never null → OTHER
                        .trade(t.getTrade() != null ? t.getTrade() : Trade.OTHER)
                        .type(t.getType())
                        .unit(t.getUnit())
                        .defaultPrice(t.getSuggestedPrice())
                    .description(t.getDescription())
                        // Copied from the shared library, not invented here — the admin insight screens
                        // filter on this so a position we shipped and later deleted is never mistaken
                        // for something a master came up with.
                        .source(CatalogItemSource.LIBRARY)
                        .build());
            }
        }
        return toCreate;
    }

    /** Composite dedup key — same name (trimmed, case-insensitive) + type + unit are a
     *  duplicate. Must match the {@code ux_catalog_items_owner_name_type_unit} index
     *  expression {@code lower(trim(name))} so a copy never inserts a row the DB rejects. */
    private static String key(CatalogItem item) {
        return item.getName().trim().toLowerCase() + "|" + item.getType() + "|" + item.getUnit();
    }

    private static String key(CatalogTemplate template) {
        return template.getName().trim().toLowerCase() + "|" + template.getType() + "|" + template.getUnit();
    }
}
