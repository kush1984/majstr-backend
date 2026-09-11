package com.majstr.backend.service;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.CalculatedMaterialRow;
import com.majstr.backend.dto.CoverageGap;
import com.majstr.backend.dto.CoverageGapKind;
import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialAvailabilityResponse;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.MaterialCoverage;
import com.majstr.backend.dto.MaterialLineRequest;
import com.majstr.backend.dto.MaterialSourceLine;
import com.majstr.backend.dto.MissingParameter;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.MasterMaterialPref;
import com.majstr.backend.entity.Material;
import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.entity.MaterialPrefKey;
import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.MasterMaterialPrefRepository;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Скільки матеріалу купити» — the estimate's works turned into a buying list (V127).
 *
 * <p><b>Nothing here is stored.</b> The calculation runs on every request, so a corrected quantity
 * in the estimate is simply reflected the next time the master opens the screen. It becomes durable
 * only when he sends it to the shopping list or into the estimate as MATERIAL lines.</p>
 *
 * <p>Four rules, each of which has silently broken this feature in an earlier draft:</p>
 * <ol>
 *   <li><b>A unit is never converted.</b> A norm is written in the POSITION's unit, so a per-м.п.
 *       position multiplies a per-м.п. norm. 15 of the 56 DRYWALL positions are LINEAR_METER and an
 *       earlier draft ran a per-m² norm against them — no error, just far too much material.</li>
 *   <li><b>A quantity the master typed is never reinterpreted.</b> No doubling for the second face
 *       of a partition, no inferred layer count: he entered the figure, we use it. The norms are
 *       written on that basis (see the V127 preamble), so board, frame and tape all measure the
 *       same thing.</li>
 *   <li><b>What we cannot answer is said out loud.</b> A position with no norm, or with norms that
 *       disagree across trades, goes to {@link MaterialCoverage} instead of being skipped. A list
 *       that looks complete and is not sends the master to the shop twice.</li>
 *   <li><b>The trade is only the FIRST rung.</b> {@code estimate_items.trade} is nullable by design
 *       (V125) and V118 files a position two trades both ship under whichever claimed it first, so
 *       the fallback on (name, unit) is the rung that actually carries the load.</li>
 * </ol>
 *
 * <p>A master may correct a coefficient, and his correction is a norm of his own that HIDES the
 * shipped one — see {@link #preferOwn} and {@code MaterialNormService}. It is resolved on the read
 * path, per norm, so one edited figure never costs him the rest of the shipped set.</p>
 */
@Service
@RequiredArgsConstructor
public class MaterialCalculatorService {

    /** Applied when neither the request nor the master's own preference says otherwise. */
    static final BigDecimal DEFAULT_WASTE_PERCENT = BigDecimal.TEN;
    static final BigDecimal MAX_WASTE_PERCENT = new BigDecimal("100");

    /** Quantity precision on the way out; the buying figure itself is always a whole unit. */
    private static final int SCALE = 3;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MM2_PER_M2 = new BigDecimal("1000000");

    /** The one material whose packaging a master parameter overrides — see {@link #sheetArea}. */
    private static final String GKL_SHEET_CODE_PREFIX = "GKL_SHEET";

    private final EstimateService estimateService;
    private final EstimateItemRepository itemRepository;
    private final MaterialNormRepository normRepository;
    private final MaterialRepository materialRepository;
    private final MasterMaterialPrefRepository prefRepository;
    private final ShoppingListService shoppingListService;

    @Transactional(readOnly = true)
    public MaterialCalculationResponse calculate(UUID estimateId, UUID ownerId,
                                                 BigDecimal wastePercent, BigDecimal perimeter) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        List<EstimateItem> works = workLines(itemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));

        Map<String, List<MaterialNorm>> byKey = normsByKey(works, ownerId);

        Map<UUID, Bucket> buckets = new LinkedHashMap<>();
        Map<UUID, PerimeterDemand> perimeterDemand = new LinkedHashMap<>();
        List<CoverageGap> gaps = new ArrayList<>();
        int covered = 0;

        for (EstimateItem item : works) {
            List<MaterialNorm> candidates = byKey.getOrDefault(NameKeys.of(item.getName()), List.of())
                    .stream()
                    .filter(n -> n.getUnit() == item.getUnit())
                    .toList();
            List<MaterialNorm> norms = rung1(candidates, item.getTrade());
            if (norms.isEmpty()) {
                if (spansSeveralTrades(candidates)) {
                    gaps.add(gap(item, CoverageGapKind.AMBIGUOUS));
                    continue;
                }
                norms = candidates;
            }
            if (norms.isEmpty()) {
                gaps.add(gap(item, CoverageGapKind.NO_NORM));
                continue;
            }
            covered++;
            for (MaterialNorm norm : norms) {
                Material material = norm.getMaterial();
                if (material == null) {
                    continue; // the recorded «checked, consumes nothing» verdict — an answer, not a gap
                }
                if (norm.getBasis() == NormBasis.PERIMETER) {
                    perimeterDemand.merge(material.getId(), new PerimeterDemand(material, norm),
                            PerimeterDemand::larger);
                    continue;
                }
                BigDecimal quantity = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
                BigDecimal amount = quantity.multiply(norm.getQtyPerUnit());
                bucket(buckets, material).add(norm, new MaterialSourceLine(
                        item.getId(), item.getName(), item.getUnit(), scaled(quantity),
                        norm.getQtyPerUnit(), norm.getId(), norm.getOwner() != null,
                        NormBasis.QUANTITY, scaled(amount)), amount);
            }
        }

        List<MissingParameter> parameters = new ArrayList<>();
        boolean havePerimeter = perimeter != null && perimeter.signum() > 0;
        for (PerimeterDemand demand : perimeterDemand.values()) {
            if (!havePerimeter) {
                parameters.add(new MissingParameter(NormBasis.PERIMETER.name(),
                        demand.material().displayName()));
                continue;
            }
            BigDecimal amount = perimeter.multiply(demand.norm().getQtyPerUnit());
            bucket(buckets, demand.material()).add(demand.norm(), new MaterialSourceLine(
                    null, null, Unit.LINEAR_METER, scaled(perimeter),
                    demand.norm().getQtyPerUnit(), demand.norm().getId(),
                    demand.norm().getOwner() != null,
                    NormBasis.PERIMETER, scaled(amount)), amount);
        }

        BigDecimal effectiveWaste = effectiveWaste(ownerId, wastePercent);
        BigDecimal sheetArea = sheetArea(pref(ownerId, MaterialPrefKey.GKL_SHEET));
        List<CalculatedMaterialLine> materials = buckets.values().stream()
                .sorted(Comparator.<Bucket>comparingInt(Bucket::rank)
                        .thenComparing(b -> b.material.getName()))
                .map(b -> line(b, effectiveWaste, sheetArea))
                .toList();

        return new MaterialCalculationResponse(
                materials,
                new MaterialCoverage(works.size(), covered, gaps),
                parameters,
                effectiveWaste,
                havePerimeter ? scaled(perimeter) : null,
                estimate.getStatus() == EstimateStatus.SIGNED);
    }

    /**
     * Can this estimate be answered at all — is there anything to buy that we know how to count?
     *
     * <p>The Матеріали entry point is HIDDEN when the answer is no. V127 ships norms for DRYWALL
     * and nothing else, so a tiler opening the screen would get every one of his positions listed
     * as a gap and an empty buying list — which reads as a broken feature rather than an absent
     * one. A trade we cannot answer for is better not offered.</p>
     *
     * <p>Deliberately its own endpoint rather than a field on {@code EstimateResponse}: that record
     * is built in ~20 places and every one of them would then pay for this lookup.</p>
     */
    @Transactional(readOnly = true)
    public MaterialAvailabilityResponse availability(UUID estimateId, UUID ownerId) {
        estimateService.loadOwned(estimateId, ownerId);
        List<EstimateItem> works = workLines(itemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
        Map<String, List<MaterialNorm>> byKey = normsByKey(works, ownerId);
        int covered = 0;
        for (EstimateItem item : works) {
            // The same unit rule the calculation itself uses: a norm in another unit is not an
            // answer for this line, and nothing is converted.
            boolean hit = byKey.getOrDefault(NameKeys.of(item.getName()), List.of()).stream()
                    .anyMatch(n -> n.getUnit() == item.getUnit());
            if (hit) {
                covered++;
            }
        }
        return new MaterialAvailabilityResponse(covered > 0, works.size(), covered);
    }

    /**
     * Send the master's final numbers to the object's shopping list. He edits on the result screen,
     * so what arrives here is his decision — nothing is re-derived, and the replacement rules that
     * keep a re-run from doubling a quantity live in {@link ShoppingListService#applyCalculated}.
     */
    @Transactional
    public ShoppingListResponse toShoppingList(UUID estimateId, UUID ownerId, MaterialApplyRequest req) {
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        List<CalculatedMaterialRow> rows = new ArrayList<>();
        resolve(req).forEach((line, material) -> rows.add(new CalculatedMaterialRow(
                material.getId(), material.displayName(), material.getUnit(), line.quantity(), null)));
        return shoppingListService.applyCalculated(
                estimate.getProject().getId(), ownerId, estimateId, rows);
    }

    // ---------------------------------------------------------------------------------------

    /**
     * The norm lookup, shared by the calculation and the availability probe so the two can never
     * disagree about whether this estimate has an answer.
     */
    private Map<String, List<MaterialNorm>> normsByKey(List<EstimateItem> works, UUID ownerId) {
        Map<String, List<MaterialNorm>> byKey = new LinkedHashMap<>();
        List<String> keys = works.stream().map(i -> NameKeys.of(i.getName())).distinct().toList();
        if (keys.isEmpty()) {
            return byKey;
        }
        for (MaterialNorm norm : preferOwn(normRepository.findAllByNameKeysForOwner(keys, ownerId))) {
            byKey.computeIfAbsent(norm.getNameKey(), k -> new ArrayList<>()).add(norm);
        }
        return byKey;
    }

    /**
     * The denominator, and both exclusions matter. A PERCENT line is a surcharge, not work — it
     * consumes nothing, and counting it would push the coverage ratio down for no reason. A
     * MATERIAL line is something the master already decided to buy; we were not asked to explain
     * it, and running it through the norms would offer him the same thing twice.
     */
    private List<EstimateItem> workLines(List<EstimateItem> items) {
        return items.stream()
                .filter(i -> i.getType() == ItemType.WORK)
                .filter(i -> i.getUnit() != Unit.PERCENT)
                .toList();
    }

    /**
     * A master's own norm HIDES the default it was forked from, and nothing else. The two are paired
     * on the natural key the unique index already uses — (trade, name, unit, material) — not on a
     * link back to the default row: a shipped norm is deleted and recreated by every catalog
     * rebuild, so a stored id would not survive one.
     *
     * <p>The collapse keeps the incoming sort order, so correcting one coefficient never reshuffles
     * the result screen.</p>
     */
    private Collection<MaterialNorm> preferOwn(List<MaterialNorm> norms) {
        Map<String, MaterialNorm> byNaturalKey = new LinkedHashMap<>();
        for (MaterialNorm norm : norms) {
            String key = norm.getTrade() + "|" + norm.getNameKey() + "|" + norm.getUnit() + "|"
                    + (norm.getMaterial() == null ? "" : norm.getMaterial().getId());
            if (byNaturalKey.get(key) == null || norm.getOwner() != null) {
                byNaturalKey.put(key, norm);
            }
        }
        return byNaturalKey.values();
    }

    private List<MaterialNorm> rung1(List<MaterialNorm> candidates, Trade trade) {
        if (trade == null) {
            return List.of();
        }
        return candidates.stream().filter(n -> n.getTrade() == trade).toList();
    }

    /**
     * Two trades ship norms for the same name and unit, and the position names neither of them. We
     * cannot tell which work this is, and picking one would put someone else's material on the list
     * — so nothing is counted and the position is named in the coverage report instead.
     */
    private boolean spansSeveralTrades(List<MaterialNorm> candidates) {
        return candidates.stream().map(MaterialNorm::getTrade).distinct().count() > 1;
    }

    private CoverageGap gap(EstimateItem item, CoverageGapKind kind) {
        return new CoverageGap(item.getId(), item.getName(), item.getUnit(),
                scaled(item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity()), kind);
    }

    private Bucket bucket(Map<UUID, Bucket> buckets, Material material) {
        return buckets.computeIfAbsent(material.getId(), k -> new Bucket(material));
    }

    private CalculatedMaterialLine line(Bucket bucket, BigDecimal effectiveWaste, BigDecimal sheetArea) {
        // A norm carrying its own allowance overrides the master's global one: that means THIS
        // material is wasted differently, not that he is careless.
        BigDecimal percent = bucket.normWaste.signum() > 0 ? bucket.normWaste : effectiveWaste;
        BigDecimal withWaste = bucket.total.multiply(HUNDRED.add(percent))
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);

        BigDecimal packageSize = packageSize(bucket.material, sheetArea);
        Integer packages = null;
        BigDecimal quantity;
        if (packageSize != null && packageSize.signum() > 0) {
            // Always UP: half a bag of putty is not sold, and being short stops the work.
            BigDecimal count = withWaste.divide(packageSize, 0, RoundingMode.CEILING);
            packages = count.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
            quantity = packageSize.multiply(BigDecimal.valueOf(packages));
        } else {
            quantity = withWaste.setScale(0, RoundingMode.CEILING);
        }
        return new CalculatedMaterialLine(
                bucket.material.getId(), bucket.material.displayName(), bucket.material.getUnit(),
                scaled(bucket.total), scaled(quantity), percent,
                packageSize == null ? null : scaled(packageSize),
                bucket.material.getPackageName(), packages, bucket.sources);
    }

    /**
     * The drywall sheet is the one material whose package the master's own habit defines: a
     * 1200×3000 sheet is 3,6 m² where the shipped default is 3,0, and rounding to the wrong sheet
     * is a wasted trip. Every other material keeps the dictionary's own packaging.
     */
    private BigDecimal packageSize(Material material, BigDecimal sheetArea) {
        if (sheetArea != null && material.getCode() != null
                && material.getCode().startsWith(GKL_SHEET_CODE_PREFIX)) {
            return sheetArea;
        }
        return material.getPackageSize();
    }

    /** «1200x2500» to 3,0 m². Anything we cannot read falls back to the dictionary's own package. */
    private BigDecimal sheetArea(String pref) {
        if (pref == null || pref.isBlank()) {
            return null;
        }
        String[] parts = pref.toLowerCase().split("[x×*]");
        if (parts.length != 2) {
            return null;
        }
        try {
            BigDecimal width = new BigDecimal(parts[0].trim());
            BigDecimal height = new BigDecimal(parts[1].trim());
            if (width.signum() <= 0 || height.signum() <= 0) {
                return null;
            }
            return width.multiply(height).divide(MM2_PER_M2, 4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal effectiveWaste(UUID ownerId, BigDecimal requested) {
        BigDecimal value = requested;
        if (value == null) {
            value = parseWaste(pref(ownerId, MaterialPrefKey.WASTE_PERCENT));
        }
        if (value == null) {
            value = DEFAULT_WASTE_PERCENT;
        }
        return value.max(BigDecimal.ZERO).min(MAX_WASTE_PERCENT);
    }

    private BigDecimal parseWaste(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String pref(UUID ownerId, MaterialPrefKey key) {
        return prefRepository.findByUserIdAndPrefKey(ownerId, key)
                .map(MasterMaterialPref::getPrefValue)
                .orElse(null);
    }

    /** Keeps the master's own order and ignores a material id that is not in the dictionary. */
    private Map<MaterialLineRequest, Material> resolve(MaterialApplyRequest req) {
        Map<MaterialLineRequest, Material> resolved = new LinkedHashMap<>();
        for (MaterialLineRequest line : req.materials()) {
            if (line.quantity().signum() <= 0) {
                continue; // he zeroed the row out — that is a removal, not a purchase of nothing
            }
            materialRepository.findById(line.materialId()).ifPresent(m -> resolved.put(line, m));
        }
        return resolved;
    }

    private BigDecimal scaled(BigDecimal value) {
        BigDecimal rounded = value.max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
        return rounded.signum() == 0 ? BigDecimal.ZERO : rounded.stripTrailingZeros();
    }

    /** One material accumulating across every position that consumes it. */
    private static final class Bucket {
        private final Material material;
        private final List<MaterialSourceLine> sources = new ArrayList<>();
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal normWaste = BigDecimal.ZERO;
        private int rank = Integer.MAX_VALUE;

        private Bucket(Material material) {
            this.material = material;
        }

        private void add(MaterialNorm norm, MaterialSourceLine source, BigDecimal amount) {
            sources.add(source);
            total = total.add(amount);
            normWaste = normWaste.max(norm.getWastePercent());
            rank = Math.min(rank, norm.getSortOrder());
        }

        private int rank() {
            return rank;
        }
    }

    /**
     * A perimeter norm is applied ONCE per material for the whole estimate, not once per position:
     * a master who lists both a wall lining and a ceiling has one room, and adding the two would
     * buy him twice the track. The larger per-metre figure wins — a wall lining needs a run at the
     * floor and one at the ceiling where a ceiling needs a single run.
     */
    private record PerimeterDemand(Material material, MaterialNorm norm) {
        private PerimeterDemand larger(PerimeterDemand other) {
            return other.norm.getQtyPerUnit().compareTo(norm.getQtyPerUnit()) > 0 ? other : this;
        }
    }
}
