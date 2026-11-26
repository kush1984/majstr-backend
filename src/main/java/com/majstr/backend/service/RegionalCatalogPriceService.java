package com.majstr.backend.service;

import com.majstr.backend.dto.RegionalCatalogPricesResponse;
import com.majstr.backend.dto.RegionalCatalogPricesResponse.Row;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalBaseline;
import com.majstr.backend.entity.RegionalIndex;
import com.majstr.backend.entity.RegionalPrice;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.CatalogItemRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * «Ціни по областях» (plan-regional-prices.md, step 2) — PRO: for every work in the master's catalog,
 * the chosen oblast's figure and the country's, by the same levels as {@link PriceHintService}:
 * the oblast's median (3+ masters there) → the country baseline × the oblast's factor («орієнтовно») →
 * nothing for that oblast. The country figure is the baseline, else the library's price.
 *
 * <p>Only medians leave here — never one master's price, name or town. Matched by
 * {@link CatalogNameKey} only, like the hint; read-only, nothing is written to his catalog.</p>
 */
@Service
@RequiredArgsConstructor
public class RegionalCatalogPriceService {

    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;
    private final CatalogItemRepository catalogRepository;
    private final CatalogTemplateRepository templateRepository;
    private final RegionalPriceRepository priceRepository;
    private final RegionalBaselineRepository baselineRepository;
    private final RegionalIndexRepository indexRepository;

    @Transactional(readOnly = true)
    public RegionalCatalogPricesResponse forRegion(UUID userId, Region region) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        featureGuard.requireFeature(user, Feature.REGIONAL_PRICES);

        Map<String, RegionalPrice> oblast = new HashMap<>();
        for (RegionalPrice p : priceRepository.findByRegion(region)) {
            if (p.getItemType() == ItemType.WORK) {
                oblast.putIfAbsent(key(p.getNameKey(), p.getUnit()), p);
            }
        }
        Map<String, RegionalBaseline> baseline = new HashMap<>();
        for (RegionalBaseline b : baselineRepository.findAll()) {
            if (b.getItemType() == ItemType.WORK) {
                baseline.putIfAbsent(key(b.getNameKey(), b.getUnit()), b);
            }
        }
        // The library by name key + unit; a position several trades ship keeps each trade's row,
        // so a catalog line takes its own trade's price first.
        Map<String, List<CatalogTemplate>> library = new HashMap<>();
        for (CatalogTemplate t : templateRepository.findByType(ItemType.WORK)) {
            if (t.getSuggestedPrice() != null && t.getSuggestedPrice().signum() > 0) {
                library.computeIfAbsent(key(CatalogNameKey.of(t.getName()), t.getUnit()), k -> new ArrayList<>())
                        .add(t);
            }
        }
        Optional<RegionalIndex> index = indexRepository.findById(region);

        List<Row> rows = new ArrayList<>();
        for (CatalogItem item : catalogRepository.findByOwnerIdAndTypeOrderByNameAsc(userId, ItemType.WORK)) {
            if (item.getUnit() == null || item.getUnit() == Unit.PERCENT) {
                continue;
            }
            String nameKey = CatalogNameKey.of(item.getName());
            if (nameKey.isEmpty()) {
                continue;
            }
            String k = key(nameKey, item.getUnit());
            RegionalPrice own = oblast.get(k);
            RegionalBaseline country = baseline.get(k);

            String level = null;
            BigDecimal regional = null;
            Integer masters = null;
            if (own != null) {
                level = "OBLAST";
                regional = own.getMedianPrice();
                masters = own.getMastersCount();
            } else if (country != null && index.isPresent()) {
                level = "OBLAST_ESTIMATE";
                regional = PriceHintService.oblastEstimate(country.getMedianPrice(), index.get().getFactor());
            }
            BigDecimal countryPrice = country != null ? country.getMedianPrice() : libraryPrice(library.get(k), item);
            if (regional != null || countryPrice != null) {
                rows.add(new Row(item.getId(), level, regional, masters, countryPrice));
            }
        }

        Instant computedAt = priceRepository.findTopByOrderByComputedAtDesc()
                .map(RegionalPrice::getComputedAt)
                .orElse(index.map(RegionalIndex::getComputedAt).orElse(null));
        return new RegionalCatalogPricesResponse(region, index.map(RegionalIndex::getFactor).orElse(null),
                computedAt, rows);
    }

    private static BigDecimal libraryPrice(List<CatalogTemplate> same, CatalogItem item) {
        if (same == null) {
            return null;
        }
        return same.stream()
                .filter(t -> t.getTrade() == item.getTrade())
                .findFirst()
                .orElse(same.getFirst())
                .getSuggestedPrice();
    }

    private static String key(String nameKey, Unit unit) {
        return nameKey + '|' + unit;
    }
}
