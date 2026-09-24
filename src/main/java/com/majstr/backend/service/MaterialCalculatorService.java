package com.majstr.backend.service;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.CalculatedMaterialRow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li><b>Only a buying decision is counted.</b> A PERCENT surcharge, a material the master already
 *       listed, and a line whose quantity is still 0 are none of them — a price-list row
 *       («Штукатурні роботи (від) — 0 м²») would otherwise buy 0 of something and name a trade
 *       nobody is buying for. What WAS counted is named by trade in {@link MaterialCoverage}; what
 *       was not stays silent (master's ruling, 2026-09-11).</li>
 *   <li><b>The POSITION's trade decides which norms may answer.</b> A norm applies when its trade is
 *       the position's, or when the position names no trade at all ({@code estimate_items.trade} is
 *       nullable by design, V125). An earlier draft fell back to (name, unit) alone whenever the
 *       trade missed, and on the master's own estimate — one drywall line, «Вирізка отворів»,
 *       among 38 painter and tiling ones — that sold him картон, ґрунтовка and шпаклівка off the
 *       МАЛЯРНІ positions: «оце все з малярки не має взагалі попадати» (his ruling, 2026-09-11).
 *       A norm filed under no trade at all still answers for anyone — see {@link #normsFor}.</li>
 * </ol>
 *
 * <p><b>Three figures are ASKED FOR, never derived</b> (see {@link NormBasis}). The room's
 * {@link NormBasis#PERIMETER} is one number for the whole estimate — one room, one perimeter. A
 * короб's {@link NormBasis#SECTION} is one number PER POSITION (V131): a короб is sold by the м.п.
 * of its length and sheathed by the розгортка of a box the position name does not describe, and a
 * прямий короб, a радіусний one and a ніша in the same estimate are three different boxes. One
 * section for all of them would be silently wrong for two — so each asks separately, and until it
 * is answered the position's SECTION materials are reported as missing parameters and left out.
 * A layer's {@link NormBasis#THICKNESS} in millimetres is the third, asked per POSITION for the
 * same reason and answered the same way (V137) — plaster, screed and levelling compound are sold
 * per m² per mm, so the millimetres ARE the bill. Unlike a розгортка a thickness has an honest
 * suggestion, which rides the ask as {@link MissingParameter#suggested()} to be PRE-FILLED and
 * still visible; nothing is ever applied on the master's behalf.</p>
 *
 * <p><b>Two habits rescale a shipped coefficient</b> — {@code PAINT_COVERAGE} × {@code PAINT_COATS}
 * for paint and {@code TILE_JOINT_MM} for grout (see {@link #coefficient}). Both are properties of
 * the MASTER, both scale their material linearly, and the scaled figure is what the arithmetic line
 * on screen reports — a coefficient he cannot see is a number he cannot check. V126 shipped four
 * further keys that were properties of the WORK, and V137 deleted them: the catalog already names
 * the tile format and the plaster bound, and one answer per master is wrong for the bathroom that
 * mixes 300×300 on the floor with 600×1200 on the wall.</p>
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

    /** The one material whose packaging a master parameter overrides — see {@link #sheetArea}.
     *  Matched EXACTLY: {@code GKL_SHEET_ARCH} is a different product with its own package, and a
     *  habit about flat sheets says nothing about an arched one. */
    private static final String GKL_SHEET_CODE = "GKL_SHEET";

    /**
     * The two materials a master's HABIT rescales, and the figures the shipped norms were written
     * against (V137). They are matched by material code, not by norm, so a coefficient corrected in
     * a later migration keeps scaling with his answer instead of quietly falling out of it.
     *
     * <p>A prefix for paint — {@code PAINT_INTERIOR}, {@code PAINT_CEILING} and V138's
     * {@code PAINT_FACADE} are all spread by the same hand — and an exact code for grout, which is
     * the only material a joint width governs. The habit is applied as a RATIO, which is what lets
     * it cross a facade norm written against a different base (6,5 м²/л, not 9): a master who
     * covers a third more than we assume covers a third more out there too. Adhesive, primer and
     * putty do not scale with either: they are consumed per m2 of surface, and their own habit is a
     * thickness, which the POSITION now answers.</p>
     */
    private static final String PAINT_CODE_PREFIX = "PAINT_";
    private static final String GROUT_CODE = "TILE_GROUT";

    /** Square metres one litre covers in ONE coat, and how many coats — the shipped 0,22 л/м². */
    public static final BigDecimal DEFAULT_PAINT_COVERAGE = new BigDecimal("9");
    public static final BigDecimal DEFAULT_PAINT_COATS = new BigDecimal("2");

    /** The joint the shipped grout figures assume, in millimetres. */
    public static final BigDecimal DEFAULT_TILE_JOINT_MM = new BigDecimal("2.5");

    /** Upper bound on a figure the master types per position — metres for a розгортка, millimetres
     *  for a thickness. One bound for both because it is a stray extra digit it looks for, not a
     *  rule of building; the PWA's own field guard uses the same number. */
    private static final BigDecimal MAX_PER_POSITION = new BigDecimal("1000");

    private final EstimateService estimateService;
    private final EstimateItemRepository itemRepository;
    private final MaterialNormRepository normRepository;
    private final MaterialRepository materialRepository;
    private final MasterMaterialPrefRepository prefRepository;
    private final ShoppingListService shoppingListService;

    @Transactional(readOnly = true)
    public MaterialCalculationResponse calculate(UUID estimateId, UUID ownerId,
                                                 BigDecimal wastePercent, BigDecimal perimeter,
                                                 String sections, String thicknesses) {
        Map<UUID, BigDecimal> section = parsePerPosition(sections);
        Map<UUID, BigDecimal> thickness = parsePerPosition(thicknesses);
        Habits habits = new Habits(ownerId);
        Estimate estimate = estimateService.loadOwned(estimateId, ownerId);
        List<EstimateItem> buyable = buyableLines(itemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
        List<EstimateItem> works = priced(buyable);
        // Nothing to count because nothing has a quantity yet — the ordinary state of an estimate
        // straight out of a bundle. The screen says that instead of «we know no norms for this
        // work», which is a different sentence and, here, a false one.
        boolean quantitiesMissing = works.isEmpty() && !buyable.isEmpty();

        Map<String, List<MaterialNorm>> byKey = normsByKey(works, ownerId);

        Map<UUID, Bucket> buckets = new LinkedHashMap<>();
        Map<UUID, PerimeterDemand> perimeterDemand = new LinkedHashMap<>();
        List<MissingParameter> parameters = new ArrayList<>();
        Set<Trade> countedTrades = new LinkedHashSet<>();
        boolean otherWorks = false;

        for (EstimateItem item : works) {
            List<MaterialNorm> norms = normsFor(item, byKey);
            if (norms.isEmpty()) {
                continue;
            }
            // The POSITION's trade, not the norm's: a norm filed under no trade answers for anyone,
            // and naming ITS trade would then answer «Гіпсокартон» for a line that is not one.
            if (item.getTrade() == null) {
                otherWorks = true;
            } else {
                countedTrades.add(item.getTrade());
            }
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
                BigDecimal quantity = item.getQuantity(); // non-null and positive — see workLines
                BigDecimal per = coefficient(norm, material, habits);
                NormBasis basis = norm.getBasis();
                if (basis == NormBasis.SECTION || basis == NormBasis.THICKNESS) {
                    // Per POSITION, not per estimate: this box's own розгортка, this layer's own
                    // millimetres, or nothing at all.
                    Map<UUID, BigDecimal> answers = basis == NormBasis.SECTION ? section : thickness;
                    BigDecimal param = answers.get(item.getId());
                    if (param == null || param.signum() <= 0) {
                        parameters.add(new MissingParameter(basis.name(), material.displayName(),
                                item.getId(), item.getName(), norm.getDefaultParam()));
                        continue;
                    }
                    BigDecimal amount = quantity.multiply(param).multiply(per);
                    bucket(buckets, material).add(norm, new MaterialSourceLine(
                            item.getId(), item.getName(), item.getUnit(), scaled(quantity),
                            per, norm.getId(), norm.getOwner() != null,
                            basis, scaled(param), scaled(amount)), amount);
                    continue;
                }
                BigDecimal amount = quantity.multiply(per);
                bucket(buckets, material).add(norm, new MaterialSourceLine(
                        item.getId(), item.getName(), item.getUnit(), scaled(quantity),
                        per, norm.getId(), norm.getOwner() != null,
                        NormBasis.QUANTITY, null, scaled(amount)), amount);
            }
        }

        boolean havePerimeter = perimeter != null && perimeter.signum() > 0;
        for (PerimeterDemand demand : perimeterDemand.values()) {
            if (!havePerimeter) {
                parameters.add(new MissingParameter(NormBasis.PERIMETER.name(),
                        demand.material().displayName(), null, null,
                        demand.norm().getDefaultParam()));
                continue;
            }
            BigDecimal amount = perimeter.multiply(demand.norm().getQtyPerUnit());
            bucket(buckets, demand.material()).add(demand.norm(), new MaterialSourceLine(
                    null, null, Unit.LINEAR_METER, scaled(perimeter),
                    demand.norm().getQtyPerUnit(), demand.norm().getId(),
                    demand.norm().getOwner() != null,
                    NormBasis.PERIMETER, null, scaled(amount)), amount);
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
                new MaterialCoverage(countedTrades.stream().map(Trade::name).toList(), otherWorks),
                parameters,
                effectiveWaste,
                havePerimeter ? scaled(perimeter) : null,
                estimate.getStatus() == EstimateStatus.SIGNED,
                quantitiesMissing);
    }

    /**
     * Can this estimate be answered at all — is there anything to buy that we know how to count?
     *
     * <p>The Матеріали entry point is HIDDEN when the answer is no. DRYWALL, TILING and PAINTER
     * have norms (V127 + V137) and the rest of the trades do not, so a floorer opening the screen
     * would get an empty buying list — which reads as a broken feature rather than an absent one.
     * A trade we cannot answer for is better not offered.</p>
     *
     * <p>Deliberately its own endpoint rather than a field on {@code EstimateResponse}: that record
     * is built in ~20 places and every one of them would then pay for this lookup.</p>
     *
     * <p><b>Quantities are no part of the question</b> — see {@link #priced}. The probe asks what
     * the estimate's position NAMES can be answered for; an estimate applied from a bundle carries
     * nothing but zeros and is precisely when the button is wanted.</p>
     */
    @Transactional(readOnly = true)
    public MaterialAvailabilityResponse availability(UUID estimateId, UUID ownerId) {
        estimateService.loadOwned(estimateId, ownerId);
        List<EstimateItem> works = buyableLines(itemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(estimateId));
        Map<String, List<MaterialNorm>> byKey = normsByKey(works, ownerId);
        // The calculation's own lookup, so the probe and the result screen can never disagree —
        // plus the one condition the probe alone has: the norm must BUY something. A «checked,
        // consumes nothing» verdict (material_id IS NULL, V127) is a complete answer for the
        // coverage line, but an estimate whose every norm is one of those has nothing to show, and
        // the master met exactly that: the «Матеріали» button opened a screen with no materials.
        boolean any = works.stream().anyMatch(item ->
                normsFor(item, byKey).stream().anyMatch(n -> n.getMaterial() != null));
        return new MaterialAvailabilityResponse(any);
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
        resolve(req).forEach((material, quantity) -> rows.add(new CalculatedMaterialRow(
                material.getId(), material.displayName(), material.getUnit(), quantity, null)));
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
     * A line that could consume something, whatever its quantity says yet. Both exclusions matter:
     * a PERCENT line is a surcharge, not work — it consumes nothing — and a MATERIAL line is
     * something the master already decided to buy, so running it through the norms would offer him
     * the same thing twice.
     */
    private List<EstimateItem> buyableLines(List<EstimateItem> items) {
        return items.stream()
                .filter(i -> i.getType() == ItemType.WORK)
                .filter(i -> i.getUnit() != Unit.PERCENT)
                .toList();
    }

    /**
     * ...and of those, the ones that are a decision to buy, which is where the quantity comes in.
     *
     * <p><b>A quantity of 0 was a live bug.</b> Masters keep their price list inside an estimate —
     * «Штукатурні роботи (від) — 0 м²» — and on the master's own test estimate 31 of 39 lines were
     * exactly that. Each one reached a norm and produced a material row of 0 («Картон захисний —
     * 0 м²», «Шпаклівка фінішна — 0 кг»), which is what «звідки у матеріалах стільки матеріалів»
     * was about. A line with no quantity is not yet a decision to buy anything.</p>
     *
     * <p><b>It is the CALCULATION's filter, never the probe's</b> — that was the second half of the
     * bug. An estimate straight out of a bundle has every quantity at zero, so a probe sharing this
     * filter answered «nothing to buy» and the Матеріали button was hidden at exactly the moment
     * the estimate was created: «я не бачу внизу того калькулятора». Whether we can answer for an
     * estimate is a property of its position NAMES, not of numbers the master has not typed yet.</p>
     */
    private List<EstimateItem> priced(List<EstimateItem> buyable) {
        return buyable.stream()
                .filter(i -> i.getQuantity() != null && i.getQuantity().signum() > 0)
                .toList();
    }

    /**
     * A master's own norm HIDES the default it was forked from, and nothing else. The two are paired
     * on the natural key the unique index already uses — (trade, name, unit, material) — not on a
     * link back to the default row: the norm a fork points at is addressed by NAME, and a catalog
     * rebuild (V82, V116, V122) recreates the templates that name carries, so anything id-shaped
     * would have to be repaired by every rebuild. Norm rows themselves are edited in place by a
     * correction migration (V130, V133) and are never deleted and re-seeded.
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

    /**
     * Which norms may answer for one position — the whole trade rule, in one place shared by the
     * calculation and the availability probe.
     *
     * <p>Two filters, and both are refusals to guess. The UNIT: a norm written for m² is not an
     * answer for the same position priced per м.п., and nothing is converted. The TRADE: the
     * position's own trade decides, so «Шпаклювання фінішне» filed under Малярні роботи never
     * reaches a DRYWALL norm. A norm carrying no trade at all is general and answers for anyone.</p>
     *
     * <p>A position with no trade of its own ({@code estimate_items.trade} is nullable by design,
     * V125 — ADDENDUM and hand-typed lines) takes every candidate, since there is nothing to
     * disagree with. Unless they span several trades: then two trades ship this name and unit, we
     * cannot tell which work it is, and picking one would put someone else's material on the list —
     * so the position is skipped and names no trade. {@code MaterialCalculatorIntegrationTest} pins
     * that the shipped catalog contains no such collision in the first place.</p>
     */
    private List<MaterialNorm> normsFor(EstimateItem item, Map<String, List<MaterialNorm>> byKey) {
        List<MaterialNorm> candidates = byKey.getOrDefault(NameKeys.of(item.getName()), List.of())
                .stream()
                .filter(n -> n.getUnit() == item.getUnit())
                .toList();
        if (item.getTrade() == null) {
            return spansSeveralTrades(candidates) ? List.of() : candidates;
        }
        return candidates.stream()
                .filter(n -> n.getTrade() == null || n.getTrade() == item.getTrade())
                .toList();
    }

    /**
     * Two DIFFERENT trades shipping this name and unit: we cannot tell which work the position is,
     * and picking one would buy someone else's material. A norm carrying NO trade is not a second
     * opinion — it answers for a position of any trade (§25) — so it can never make the answer
     * ambiguous and is left out before the count. Counting it as a distinct value refused positions
     * that had exactly one real candidate.
     */
    private boolean spansSeveralTrades(List<MaterialNorm> candidates) {
        return candidates.stream()
                .map(MaterialNorm::getTrade)
                .filter(trade -> trade != null)
                .distinct()
                .count() > 1;
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
     * The coefficient actually used, which is the shipped one rescaled by the master's habits.
     *
     * <p>Two habits genuinely are habits — how thickly he paints, and how wide he leaves a joint —
     * and both scale their material LINEARLY, so a stored figure is enough and no second norm is
     * needed. The scale is 1 when he has said nothing, so the shipped figure stands.</p>
     *
     * <p><b>An owned norm is never rescaled.</b> A master who corrected a coefficient has already
     * told us the number he buys against; multiplying his answer by his own habit would apply the
     * same opinion twice, and he has no way to see that it happened.</p>
     */
    private BigDecimal coefficient(MaterialNorm norm, Material material, Habits habits) {
        BigDecimal per = norm.getQtyPerUnit();
        if (norm.getOwner() != null) {
            return per;
        }
        String code = material.getCode();
        if (code == null) {
            return per;
        }
        if (code.startsWith(PAINT_CODE_PREFIX)) {
            return scaleBy(per, habits.paint());
        }
        if (GROUT_CODE.equals(code)) {
            return scaleBy(per, habits.joint());
        }
        return per;
    }

    /**
     * The two rescaling habits, each read on FIRST demand and never twice.
     *
     * <p>Not a micro-optimisation: most estimates buy neither paint nor grout, and a drywall job
     * asking the database twice for an answer nothing will consult is work done for nobody. It also
     * keeps the read honest — a pref row is touched only when a material on THIS list scales with
     * it, which is what a master's own norm relies on when it declines to be rescaled at all.</p>
     */
    private final class Habits {
        private final UUID ownerId;
        private BigDecimal paint;
        private BigDecimal joint;
        private boolean paintRead;
        private boolean jointRead;

        private Habits(UUID ownerId) {
            this.ownerId = ownerId;
        }

        private BigDecimal paint() {
            if (!paintRead) {
                paint = paintScale(ownerId);
                paintRead = true;
            }
            return paint;
        }

        private BigDecimal joint() {
            if (!jointRead) {
                joint = jointScale(ownerId);
                jointRead = true;
            }
            return joint;
        }
    }

    private BigDecimal scaleBy(BigDecimal per, BigDecimal scale) {
        return scale == null ? per : per.multiply(scale).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * «Скільки м² з літра» × «скільки шарів», against the pair the shipped norms were written for.
     * Null when he has said nothing or typed something we cannot read — the shipped figure then
     * stands untouched, which is the same outcome as a scale of 1 and one fewer thing to get wrong.
     */
    private BigDecimal paintScale(UUID ownerId) {
        BigDecimal coverage = positive(pref(ownerId, MaterialPrefKey.PAINT_COVERAGE));
        BigDecimal coats = positive(pref(ownerId, MaterialPrefKey.PAINT_COATS));
        if (coverage == null && coats == null) {
            return null;
        }
        BigDecimal effectiveCoverage = coverage == null ? DEFAULT_PAINT_COVERAGE : coverage;
        BigDecimal effectiveCoats = coats == null ? DEFAULT_PAINT_COATS : coats;
        return effectiveCoats.divide(effectiveCoverage, 6, RoundingMode.HALF_UP)
                .divide(DEFAULT_PAINT_COATS.divide(DEFAULT_PAINT_COVERAGE, 6, RoundingMode.HALF_UP),
                        6, RoundingMode.HALF_UP);
    }

    /** Grout fills the joint, so it scales with the joint's width and nothing else. */
    private BigDecimal jointScale(UUID ownerId) {
        BigDecimal joint = positive(pref(ownerId, MaterialPrefKey.TILE_JOINT_MM));
        return joint == null ? null : joint.divide(DEFAULT_TILE_JOINT_MM, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(String raw) {
        BigDecimal value = parseWaste(raw);
        return value == null || value.signum() <= 0 ? null : value;
    }

    /**
     * The drywall sheet is the one material whose package the master's own habit defines: a
     * 1200×3000 sheet is 3,6 m² where the shipped default is 3,0, and rounding to the wrong sheet
     * is a wasted trip. Every other material keeps the dictionary's own packaging.
     */
    private BigDecimal packageSize(Material material, BigDecimal sheetArea) {
        if (sheetArea != null && GKL_SHEET_CODE.equals(material.getCode())) {
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

    /**
     * The master's lines folded onto the MATERIAL: his order is kept, a material id that is not in
     * the dictionary is ignored, and <b>two lines naming the same material are SUMMED</b>.
     *
     * <p>Keyed by the material and not by the request line, and the difference is money. Two lines
     * naming one material are ordinary — the same плита is consumed by several positions — and
     * {@code MaterialLineRequest} is a record, so {@code (material, 12)} sent twice is ONE map key:
     * the old version dropped the second silently and asked him to buy 12 where he needs 24.
     * {@code ShoppingListService.mergeInput} sums by dedup key and would have caught a pair that
     * reached it, which is exactly why this was invisible — the pair never got there.</p>
     */
    private Map<Material, BigDecimal> resolve(MaterialApplyRequest req) {
        Map<Material, BigDecimal> resolved = new LinkedHashMap<>();
        for (MaterialLineRequest line : req.materials()) {
            if (line.quantity().signum() <= 0) {
                continue; // he zeroed the row out — that is a removal, not a purchase of nothing
            }
            materialRepository.findById(line.materialId())
                    .ifPresent(m -> resolved.merge(m, line.quantity(), BigDecimal::add));
        }
        return resolved;
    }

    /**
     * The per-position figures the master typed, as they ride the query string:
     * «uuid:0.4,uuid:0.55». Shared by SECTION (metres) and THICKNESS (millimetres), which travel as
     * two separate parameters — one map per QUESTION, because one короб line can be both a box with
     * a розгортка and a layer with a thickness, and merging them would answer one with the other.
     *
     * <p>One compact scalar parameter rather than a repeated one or a request body, so asking for a
     * переріз does not turn the calculation into a POST — it stores nothing and stays a view of the
     * estimate (V127).</p>
     *
     * <p>A malformed or unknown entry is <b>ignored, never rejected</b>. The id belongs to an
     * estimate line the master is still editing, so a stale one is ordinary — and the consequence of
     * ignoring it is that the position asks for its figure again, which is a screen he can act on.
     * A 400 would be an empty screen with no way forward, for a figure that is optional by design.
     * </p>
     */
    static Map<UUID, BigDecimal> parsePerPosition(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> sections = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                continue;
            }
            try {
                UUID id = UUID.fromString(entry.substring(0, colon).trim());
                BigDecimal value = new BigDecimal(entry.substring(colon + 1).trim().replace(',', '.'));
                // Bounded for the same reason the PWA bounds its own field (B-18): the figure is
                // multiplied into a quantity, so a stray extra digit is not a big answer, it is a
                // shopping list nobody can read. Out of range is IGNORED, not rejected — the
                // position then asks again, which is a screen the master can act on.
                if (value.signum() > 0 && value.compareTo(MAX_PER_POSITION) <= 0) {
                    sections.put(id, value);
                }
            } catch (IllegalArgumentException e) {
                // not an id, or not a number — the position simply asks again
            }
        }
        return sections;
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
