package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogInsights;
import com.majstr.backend.entity.CatalogInsightDismissal;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.CatalogUpdateNotice;
import com.majstr.backend.entity.CatalogUpdateNoticeKind;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PriceInsightCandidate;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogInsightDismissalRepository;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.CatalogUpdateNoticeRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.PriceInsightCandidateRepository;
import com.majstr.backend.service.catalog.CatalogNameKey;
import com.majstr.backend.service.catalog.PriceInsightMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Community prices: what masters actually charge, read back into the default catalog.
 *
 * <p><b>Source of truth is ESTIMATE lines, not the catalog.</b> A master's catalog price drifts
 * far less often than what he actually quotes — he edits the number right on the estimate and
 * rarely goes back to update his library. Aggregating the catalog would miss most of the real
 * market movement.
 *
 * <p><b>Two-level median</b> (per {@code EstimateItemRepository#aggregatePerMasterWorkPrices}):
 * one master's many draft/duplicate lines collapse to ONE number before he is compared against
 * anyone else, so a prolific master's own habits can never out-vote three people who each priced
 * the job once. The cross-master step then trims outliers (IQR) and requires at least
 * {@link PriceInsightMath#MIN_MASTERS} masters to survive the trim — see {@link PriceInsightMath}.
 *
 * <p><b>Nothing here writes to a master's own data by itself.</b> {@link #weeklyRefresh()} only
 * rebuilds the candidate queue; {@link #applyPriceDrift} only updates the SHARED default and
 * queues a notice — a master's own {@code catalog_items} row changes only when THEY accept that
 * notice ({@code CatalogController#acceptUpdateNotice}), and only if it still carries the OLD
 * price (self-edited prices are never touched — the same rule every other catalog write in this
 * project follows).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PriceInsightService {

    private final EstimateItemRepository estimateItemRepository;
    private final CatalogTemplateRepository templateRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final PriceInsightCandidateRepository candidateRepository;
    private final CatalogInsightDismissalRepository dismissalRepository;
    private final CatalogUpdateNoticeRepository noticeRepository;

    public record RefreshSummary(int priceDrift, int newPosition, int rejectedForLowN) {
    }

    /** Grouping key for the cross-master fold: the fuzzy name, unit and item type. */
    private record GroupKey(String nameKey, String type, String unit) {
    }

    /**
     * Rebuilds the whole candidate queue from scratch. Never applies a price, never writes a
     * notice — those only happen through {@link #applyPriceDrift}, by an admin's hand.
     */
    @Transactional
    public RefreshSummary weeklyRefresh() {
        Map<String, CatalogTemplate> defaults = defaultsByKey();
        Set<String> dismissedPriceDrift = dismissedKeys(CatalogInsightKind.PRICE_DRIFT);
        Set<String> dismissedNewPosition = dismissedKeys(CatalogInsightKind.NEW_POSITION);

        List<EstimateItemRepository.PerMasterPriceRow> rows = estimateItemRepository.aggregatePerMasterWorkPrices();
        Map<GroupKey, List<EstimateItemRepository.PerMasterPriceRow>> groups = new HashMap<>();
        for (EstimateItemRepository.PerMasterPriceRow row : rows) {
            String key = CatalogNameKey.of(row.getName());
            if (key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(new GroupKey(key, row.getType(), row.getUnit()), k -> new ArrayList<>()).add(row);
        }

        List<PriceInsightCandidate> priceDrift = new ArrayList<>();
        List<PriceInsightCandidate> newPosition = new ArrayList<>();
        int rejectedForLowN = 0;

        for (Map.Entry<GroupKey, List<EstimateItemRepository.PerMasterPriceRow>> entry : groups.entrySet()) {
            GroupKey key = entry.getKey();
            List<EstimateItemRepository.PerMasterPriceRow> groupRows = entry.getValue();

            List<BigDecimal> perMasterValues = groupRows.stream()
                    .collect(Collectors.groupingBy(EstimateItemRepository.PerMasterPriceRow::getMasterId,
                            Collectors.mapping(EstimateItemRepository.PerMasterPriceRow::getPerMasterMedianPrice,
                                    Collectors.toList())))
                    .values().stream()
                    // A master with more than one raw spelling under the same fuzzy key still
                    // counts once — folded to his own median across those spellings.
                    .map(vals -> vals.size() == 1 ? vals.get(0) : PriceInsightMath.median(vals))
                    .toList();

            PriceInsightMath.TrimmedStats stats = PriceInsightMath.trimmedMedian(perMasterValues);
            if (stats == null) {
                rejectedForLowN++;
                continue;
            }

            String sampleName = mostCorroboratedSpelling(groupRows);
            Instant firstSeen = groupRows.stream()
                    .map(EstimateItemRepository.PerMasterPriceRow::getFirstSeen)
                    .min(Instant::compareTo).orElse(Instant.now());
            String category = groupRows.stream()
                    .map(EstimateItemRepository.PerMasterPriceRow::getCategory)
                    .filter(Objects::nonNull).findFirst().orElse(null);

            CatalogTemplate matched = defaults.get(key.nameKey());
            if (matched != null) {
                if (dismissedPriceDrift.contains(key.nameKey())) {
                    continue;
                }
                priceDrift.add(candidateOf(key, sampleName, category, firstSeen, stats, matched));
            } else {
                if (dismissedNewPosition.contains(key.nameKey())) {
                    continue;
                }
                newPosition.add(candidateOf(key, sampleName, category, firstSeen, stats, null));
            }
        }

        candidateRepository.deleteAllByKind(CatalogInsightKind.PRICE_DRIFT);
        candidateRepository.deleteAllByKind(CatalogInsightKind.NEW_POSITION);
        candidateRepository.saveAll(priceDrift);
        candidateRepository.saveAll(newPosition);

        log.info("price-insight weekly refresh: {} price-drift, {} new-position candidates, "
                        + "{} groups rejected for fewer than {} masters after trim",
                priceDrift.size(), newPosition.size(), rejectedForLowN, PriceInsightMath.MIN_MASTERS);
        return new RefreshSummary(priceDrift.size(), newPosition.size(), rejectedForLowN);
    }

    private PriceInsightCandidate candidateOf(GroupKey key, String sampleName, String category, Instant firstSeen,
                                               PriceInsightMath.TrimmedStats stats, CatalogTemplate matchedDefault) {
        return PriceInsightCandidate.builder()
                .kind(matchedDefault != null ? CatalogInsightKind.PRICE_DRIFT : CatalogInsightKind.NEW_POSITION)
                .nameKey(key.nameKey())
                .sampleName(sampleName)
                .itemType(ItemType.valueOf(key.type()))
                .unit(Unit.valueOf(key.unit()))
                .category(category)
                .catalogTemplateId(matchedDefault != null ? matchedDefault.getId() : null)
                .currentDefaultPrice(matchedDefault != null ? matchedDefault.getSuggestedPrice() : null)
                .proposedPrice(stats.median())
                .mastersCount(stats.count())
                .minPrice(stats.min())
                .maxPrice(stats.max())
                .firstSeen(firstSeen)
                .build();
    }

    /** The spelling used by the most masters within the group — same "most corroborated wins"
     *  rule {@code AdminCatalogInsightsService#newPositions} already applies. */
    private static String mostCorroboratedSpelling(List<EstimateItemRepository.PerMasterPriceRow> rows) {
        Map<String, Long> countByRawKey = rows.stream().collect(
                Collectors.groupingBy(EstimateItemRepository.PerMasterPriceRow::getRawKey, Collectors.counting()));
        String bestRawKey = countByRawKey.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        return rows.stream().filter(r -> r.getRawKey().equals(bestRawKey))
                .findFirst().map(EstimateItemRepository.PerMasterPriceRow::getName)
                .orElseThrow();
    }

    /** Every default, keyed by its {@link CatalogNameKey} comparison key — later duplicates lose
     *  (V71 removed the real ones). A small deliberate duplicate of {@code AdminCatalogInsightsService
     *  #defaultsByKey} rather than a shared repository default method: the latter looked cleaner but
     *  broke under Mockito, which does not execute a mocked interface's real default-method body —
     *  every existing test stubbing {@code templateRepository.findAll()} silently stopped being
     *  consulted. Eight lines duplicated once is cheaper than that class of test breakage. */
    private Map<String, CatalogTemplate> defaultsByKey() {
        Map<String, CatalogTemplate> byKey = new HashMap<>();
        for (CatalogTemplate t : templateRepository.findAll()) {
            String key = CatalogNameKey.of(t.getName());
            if (!key.isEmpty()) {
                byKey.putIfAbsent(key, t);
            }
        }
        return byKey;
    }

    private Set<String> dismissedKeys(CatalogInsightKind kind) {
        return dismissalRepository.findByKind(kind).stream()
                .map(CatalogInsightDismissal::getNameKey)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<CatalogInsights.PriceDrift> listPriceDrift() {
        return candidateRepository.findByKindOrderByMastersCountDescSampleNameAsc(CatalogInsightKind.PRICE_DRIFT)
                .stream()
                .map(c -> new CatalogInsights.PriceDrift(
                        c.getId(), c.getNameKey(), c.getSampleName(), c.getItemType(), c.getUnit(), c.getCategory(),
                        c.getCatalogTemplateId(), c.getCurrentDefaultPrice(), c.getProposedPrice(),
                        c.getMastersCount(), c.getMinPrice(), c.getMaxPrice(), c.getFirstSeen()))
                .toList();
    }

    /** Estimate-sourced NEW_POSITION candidates, in the same shape the catalog-sourced list
     *  ({@code AdminCatalogInsightsService#newPositions}) already returns — that method merges
     *  this in rather than standing up a second screen. */
    @Transactional(readOnly = true)
    public List<CatalogInsights.NewPosition> listNewPositionFromEstimates() {
        return candidateRepository.findByKindOrderByMastersCountDescSampleNameAsc(CatalogInsightKind.NEW_POSITION)
                .stream()
                .map(c -> new CatalogInsights.NewPosition(
                        c.getNameKey(), c.getSampleName(), c.getItemType(), c.getUnit(), c.getCategory(),
                        c.getMastersCount(), c.getFirstSeen(), c.getProposedPrice(), c.getMinPrice(), c.getMaxPrice()))
                .toList();
    }

    /**
     * Updates the shared default's price and queues a notice for every master whose OWN
     * {@code LIBRARY} item still carries the exact old price — never one who edited it himself.
     * The master's own catalog is untouched here; it only changes when they accept the notice.
     */
    @Transactional
    public void applyPriceDrift(UUID candidateId, UUID actorId) {
        PriceInsightCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Price-drift candidate not found: " + candidateId));
        if (candidate.getKind() != CatalogInsightKind.PRICE_DRIFT) {
            throw new ResourceNotFoundException("Not a price-drift candidate: " + candidateId);
        }
        CatalogTemplate template = templateRepository.findById(candidate.getCatalogTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Catalog template not found: " + candidate.getCatalogTemplateId()));

        BigDecimal oldPrice = template.getSuggestedPrice();
        BigDecimal newPrice = candidate.getProposedPrice();
        template.setSuggestedPrice(newPrice);
        // addedInVersion left as-is on purpose — mirrors AdminCatalogTemplateService.update: an
        // edit must not re-propagate to masters who already copied the item.

        List<CatalogItem> eligible = catalogItemRepository.findLibraryItemsAtPrice(oldPrice).stream()
                .filter(item -> candidate.getNameKey().equals(CatalogNameKey.of(item.getName())))
                .toList();

        // One fresh row per eligible master — the queue, not a slot, so repricing several
        // positions for the same master in one week never drops all but the last (see V94).
        for (CatalogItem item : eligible) {
            noticeRepository.save(CatalogUpdateNotice.builder()
                    .userId(item.getOwner().getId())
                    .kind(CatalogUpdateNoticeKind.PRICE_DRIFT)
                    .positionName(candidate.getSampleName())
                    .oldPrice(oldPrice)
                    .newPrice(newPrice)
                    .build());
        }

        candidateRepository.delete(candidate);
        log.info("admin {} applied price-drift for '{}': {} -> {}, notified {} masters",
                actorId, candidate.getSampleName(), oldPrice, newPrice, eligible.size());
    }
}
