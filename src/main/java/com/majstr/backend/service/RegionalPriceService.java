package com.majstr.backend.service;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalBaseline;
import com.majstr.backend.entity.RegionalIndex;
import com.majstr.backend.entity.RegionalPrice;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateItemRepository.PerMasterPriceRow;
import com.majstr.backend.repository.RegionalBaselineRepository;
import com.majstr.backend.repository.RegionalIndexRepository;
import com.majstr.backend.repository.RegionalPriceRepository;
import com.majstr.backend.service.catalog.CatalogNameKey;
import com.majstr.backend.service.catalog.CatalogRenames;
import com.majstr.backend.service.catalog.PriceInsightMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prices by oblast, step 1 (V222 + V223, plan-regional-prices.md «How it is computed»): run weekly
 * beside the national community median, read by the admin only. Everything comes from the SAME
 * per-master rows the national median uses ({@link EstimateItemRepository#aggregatePerMasterWorkPrices}:
 * one median per master per exact spelling, WORK lines of non-rejected, non-sample estimates plus his
 * own catalog prices — V225), folded
 * here to ONE number per master per position (fuzzy name + type + unit).
 *
 * <ol>
 *   <li><b>Position × oblast</b> ({@code regional_price}) — the national rule applied inside one
 *       oblast: the oblast's masters' numbers, IQR-trimmed, 3+ left → their median.</li>
 *   <li><b>Country baseline</b> ({@code regional_baseline}) — one vote per OBLAST: each oblast's
 *       plain median of its own masters (any count, even one), then the median of those, kept when
 *       3+ oblasts price the position. Not the V94 national median (one vote per master), which
 *       leans towards the few oblasts where most masters work.</li>
 *   <li><b>Oblast factor</b> ({@code regional_index}) — every oblast master's number on a position
 *       that has a baseline, as a percent of it; one ratio per master (the median of his); the
 *       IQR-trimmed median across 3+ masters, ÷ 100, three decimals. One factor per oblast, so a thin
 *       oblast gets an «орієнтовно» (baseline × factor) long before its positions have medians.</li>
 * </ol>
 *
 * <p>A master with no oblast counts in the national median and nowhere here. No figure is typed
 * by hand: a position without 3 oblasts has no baseline, an oblast without 3 masters no factor.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegionalPriceService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Oblasts that must price a position before it has a country baseline — the same «three
     *  independent voices» floor as {@link PriceInsightMath#MIN_MASTERS}, one level up. */
    static final int MIN_REGIONS = 3;

    private final EstimateItemRepository estimateItemRepository;
    private final RegionalPriceRepository priceRepository;
    private final RegionalIndexRepository indexRepository;
    private final RegionalBaselineRepository baselineRepository;
    private final CatalogRenames renames;

    public record RefreshSummary(int positions, int baselines, int regionsWithIndex) {
    }

    private record GroupKey(String nameKey, String type, String unit) {
    }

    /** Rebuilds the three tables from scratch. Writes nothing a master owns. Its own transaction, so the
     *  weekly job's national run survives a failure here. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshSummary weeklyRefresh() {
        Instant now = Instant.now();
        Map<GroupKey, List<PerMasterPriceRow>> groups = new LinkedHashMap<>();
        // An old library wording counts as the wording it became — the national run does the same.
        CatalogRenames.Lookup renamed = renames.load();
        for (PerMasterPriceRow row : estimateItemRepository.aggregatePerMasterWorkPrices()) {
            String key = CatalogNameKey.of(renamed.current(row.getName(), row.getUnit()));
            if (!key.isEmpty()) {
                groups.computeIfAbsent(new GroupKey(key, row.getType(), row.getUnit()), k -> new ArrayList<>()).add(row);
            }
        }

        List<RegionalPrice> prices = new ArrayList<>();
        List<RegionalBaseline> baselines = new ArrayList<>();
        // region → master → his ratios to the country baseline, one per position (in percent)
        Map<Region, Map<UUID, List<BigDecimal>>> ratios = new EnumMap<>(Region.class);
        Map<Region, Set<GroupKey>> comparedPositions = new EnumMap<>(Region.class);

        for (Map.Entry<GroupKey, List<PerMasterPriceRow>> entry : groups.entrySet()) {
            GroupKey key = entry.getKey();
            List<PerMasterPriceRow> rows = entry.getValue();
            Map<UUID, BigDecimal> perMaster = perMasterValue(rows);
            Map<UUID, Region> regionOf = regionOf(rows);

            Map<Region, List<BigDecimal>> byRegion = new EnumMap<>(Region.class);
            perMaster.forEach((master, value) -> {
                Region region = regionOf.get(master);
                if (region != null) {
                    byRegion.computeIfAbsent(region, r -> new ArrayList<>()).add(value);
                }
            });
            String sampleName = mostCorroboratedSpelling(currentSpellings(rows, renamed));
            byRegion.forEach((region, values) -> {
                PriceInsightMath.TrimmedStats stats = PriceInsightMath.trimmedMedian(values);
                if (stats != null) {
                    prices.add(RegionalPrice.builder()
                            .region(region).nameKey(key.nameKey()).sampleName(sampleName)
                            .itemType(ItemType.valueOf(key.type())).unit(Unit.valueOf(key.unit()))
                            .medianPrice(stats.median()).mastersCount(stats.count())
                            .minPrice(stats.min().setScale(2, RoundingMode.HALF_UP))
                            .maxPrice(stats.max().setScale(2, RoundingMode.HALF_UP))
                            .computedAt(now).build());
                }
            });

            // One vote per oblast: its own masters' plain median, then the median of the oblasts.
            if (byRegion.size() < MIN_REGIONS) {
                continue;
            }
            List<BigDecimal> oblastMedians = byRegion.values().stream().map(PriceInsightMath::median).toList();
            BigDecimal baseline = PriceInsightMath.median(oblastMedians).setScale(2, RoundingMode.HALF_UP);
            if (baseline.signum() <= 0) {
                continue;
            }
            baselines.add(RegionalBaseline.builder()
                    .nameKey(key.nameKey()).sampleName(sampleName)
                    .itemType(ItemType.valueOf(key.type())).unit(Unit.valueOf(key.unit()))
                    .medianPrice(baseline).regionsCount(byRegion.size()).computedAt(now).build());
            perMaster.forEach((master, value) -> {
                Region region = regionOf.get(master);
                if (region != null) {
                    BigDecimal percent = value.multiply(HUNDRED).divide(baseline, 4, RoundingMode.HALF_UP);
                    ratios.computeIfAbsent(region, r -> new HashMap<>())
                            .computeIfAbsent(master, m -> new ArrayList<>()).add(percent);
                    comparedPositions.computeIfAbsent(region, r -> new HashSet<>()).add(key);
                }
            });
        }

        List<RegionalIndex> indexes = new ArrayList<>();
        ratios.forEach((region, byMaster) -> {
            List<BigDecimal> perMasterRatio = byMaster.values().stream().map(PriceInsightMath::median).toList();
            PriceInsightMath.TrimmedStats stats = PriceInsightMath.trimmedMedian(perMasterRatio);
            if (stats != null && stats.median().signum() > 0) {
                indexes.add(RegionalIndex.builder()
                        .region(region)
                        .factor(stats.median().divide(HUNDRED, 3, RoundingMode.HALF_UP))
                        .mastersCount(stats.count())
                        .positionsCount(comparedPositions.get(region).size())
                        .computedAt(now).build());
            }
        });

        priceRepository.deleteAllRows();
        baselineRepository.deleteAllRows();
        indexRepository.deleteAllRows();
        priceRepository.saveAll(prices);
        baselineRepository.saveAll(baselines);
        indexRepository.saveAll(indexes);
        log.info("regional prices weekly refresh: {} position×oblast medians, {} country baselines, {} oblast factors",
                prices.size(), baselines.size(), indexes.size());
        return new RefreshSummary(prices.size(), baselines.size(), indexes.size());
    }

    /** One number per master — several spellings under one fuzzy key fold to their median, as in
     *  the national run. */
    private static Map<UUID, BigDecimal> perMasterValue(List<PerMasterPriceRow> rows) {
        Map<UUID, List<BigDecimal>> values = new LinkedHashMap<>();
        for (PerMasterPriceRow row : rows) {
            values.computeIfAbsent(row.getMasterId(), m -> new ArrayList<>()).add(row.getPerMasterMedianPrice());
        }
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        values.forEach((master, list) -> out.put(master, list.size() == 1 ? list.get(0) : PriceInsightMath.median(list)));
        return out;
    }

    private static Map<UUID, Region> regionOf(List<PerMasterPriceRow> rows) {
        Map<UUID, Region> out = new HashMap<>();
        for (PerMasterPriceRow row : rows) {
            if (row.getRegion() != null) {
                out.put(row.getMasterId(), Region.valueOf(row.getRegion()));
            }
        }
        return out;
    }

    /** The rows under today's wording, when any — so the name shown is never one the library dropped. */
    private static List<PerMasterPriceRow> currentSpellings(List<PerMasterPriceRow> rows, CatalogRenames.Lookup renamed) {
        List<PerMasterPriceRow> current = rows.stream().filter(r -> !renamed.renamed(r.getName(), r.getUnit())).toList();
        return current.isEmpty() ? rows : current;
    }

    /** The spelling used by the most masters — the national run's rule. */
    private static String mostCorroboratedSpelling(List<PerMasterPriceRow> rows) {
        Map<String, Integer> masters = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        for (PerMasterPriceRow row : rows) {
            masters.merge(row.getRawKey(), 1, Integer::sum);
            nameOf.putIfAbsent(row.getRawKey(), row.getName());
        }
        String best = masters.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return nameOf.get(best);
    }
}
