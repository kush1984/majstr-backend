package com.majstr.backend.service;

import com.majstr.backend.dto.PriceHintResponse;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalBaseline;
import com.majstr.backend.entity.RegionalPrice;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.RegionalBaselineRepository;
import com.majstr.backend.repository.RegionalIndexRepository;
import com.majstr.backend.repository.RegionalPriceRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.catalog.CatalogNameKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The market price under the price field (plan-regional-prices.md, step 4) — a master's request:
 * «коли я ставлю ціну за роботу, щоб воно підказувало, яка ± ціна на таку позицію по ринку».
 *
 * <p>Levels, most precise first, all from data we already hold — nothing is fetched from another
 * site (the plan's «Market prices from other sources» says why):</p>
 * <ol>
 *   <li>{@code OBLAST} — the position's median among 3+ masters of his oblast ({@code regional_price});</li>
 *   <li>{@code OBLAST_ESTIMATE} — the country baseline × his oblast's factor, rounded to 10 ₴;</li>
 *   <li>{@code COUNTRY} — the library's price (market-checked by V176 / V224), his own trades' row
 *       first when the library ships the position under several.</li>
 * </ol>
 *
 * <p>A position is found by {@link CatalogNameKey} only — case, punctuation, connecting words and word
 * order. No fuzzy match: a hint for a merely similar job is a wrong number the master may trust.
 * Works only: the regional tables and the library hold no materials.</p>
 */
@Service
@RequiredArgsConstructor
public class PriceHintService {

    private static final BigDecimal TEN = BigDecimal.TEN;

    private final UserRepository userRepository;
    private final RegionalPriceRepository priceRepository;
    private final RegionalBaselineRepository baselineRepository;
    private final RegionalIndexRepository indexRepository;
    private final CatalogTemplateRepository templateRepository;

    @Transactional(readOnly = true)
    public Optional<PriceHintResponse> hint(UUID userId, String name, Unit unit) {
        String key = CatalogNameKey.of(name);
        if (key.isEmpty() || unit == null || unit == Unit.PERCENT) {
            return Optional.empty();
        }
        User user = userRepository.findWithTradesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Region region = user.getRegion();
        if (region != null) {
            Optional<RegionalPrice> own = priceRepository
                    .findFirstByRegionAndNameKeyAndItemTypeAndUnit(region, key, ItemType.WORK, unit);
            if (own.isPresent()) {
                RegionalPrice p = own.get();
                return Optional.of(new PriceHintResponse("OBLAST", p.getMedianPrice(), p.getMastersCount(),
                        region, p.getComputedAt()));
            }
            Optional<RegionalBaseline> baseline = baselineRepository
                    .findFirstByNameKeyAndItemTypeAndUnit(key, ItemType.WORK, unit);
            if (baseline.isPresent()) {
                Optional<PriceHintResponse> estimate = indexRepository.findById(region).map(index ->
                        new PriceHintResponse("OBLAST_ESTIMATE",
                                oblastEstimate(baseline.get().getMedianPrice(), index.getFactor()),
                                null, region, index.getComputedAt()));
                if (estimate.isPresent()) {
                    return estimate;
                }
            }
        }
        List<CatalogTemplate> same = templateRepository.findByTypeAndUnit(ItemType.WORK, unit).stream()
                .filter(t -> t.getSuggestedPrice() != null && t.getSuggestedPrice().signum() > 0)
                .filter(t -> key.equals(CatalogNameKey.of(t.getName())))
                .toList();
        return same.stream()
                .filter(t -> user.getTrades().contains(t.getTrade()))
                .findFirst()
                .or(() -> same.stream().findFirst())
                .map(t -> new PriceHintResponse("COUNTRY", t.getSuggestedPrice(), null, null, null));
    }

    /** Level 2: the country baseline × the oblast's factor, to 10 ₴ — «орієнтовно» says it is not a median. */
    static BigDecimal oblastEstimate(BigDecimal baseline, BigDecimal factor) {
        return baseline.multiply(factor).divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN);
    }
}
