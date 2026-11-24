package com.majstr.backend.service;

import com.majstr.backend.dto.RegionalPriceSummary;
import com.majstr.backend.entity.Region;
import com.majstr.backend.entity.RegionalIndex;
import com.majstr.backend.entity.RegionalPrice;
import com.majstr.backend.repository.RegionalBaselineRepository;
import com.majstr.backend.repository.RegionalIndexRepository;
import com.majstr.backend.repository.RegionalPriceRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** The admin's view of prices by oblast (V222): is there enough data yet, and where. */
@Service
@RequiredArgsConstructor
public class AdminRegionalPriceService {

    private final UserRepository userRepository;
    private final RegionalPriceRepository priceRepository;
    private final RegionalIndexRepository indexRepository;
    private final RegionalBaselineRepository baselineRepository;

    /** Every oblast, most masters first — an empty one is a fact worth seeing too. */
    @Transactional(readOnly = true)
    public RegionalPriceSummary summary() {
        Map<Region, Long> masters = new EnumMap<>(Region.class);
        userRepository.countMastersByRegion().forEach(r -> masters.put(r.getRegion(), r.getMasters()));
        Map<Region, Long> positions = new EnumMap<>(Region.class);
        priceRepository.countByRegion().forEach(r -> positions.put(r.getRegion(), r.getPositions()));
        List<RegionalIndex> indexList = indexRepository.findAll();
        Map<Region, RegionalIndex> indexes = new EnumMap<>(Region.class);
        indexList.forEach(i -> indexes.put(i.getRegion(), i));

        List<RegionalPriceSummary.Row> rows = Arrays.stream(Region.values())
                .map(region -> {
                    RegionalIndex index = indexes.get(region);
                    return new RegionalPriceSummary.Row(region,
                            masters.getOrDefault(region, 0L),
                            positions.getOrDefault(region, 0L),
                            index == null ? null : index.getFactor(),
                            index == null ? 0 : index.getMastersCount(),
                            index == null ? 0 : index.getPositionsCount());
                })
                .sorted(Comparator.comparingLong(RegionalPriceSummary.Row::masters).reversed()
                        .thenComparing(RegionalPriceSummary.Row::region))
                .toList();

        Instant computedAt = Stream.concat(
                        indexList.stream().map(RegionalIndex::getComputedAt),
                        priceRepository.findTopByOrderByComputedAtDesc().map(RegionalPrice::getComputedAt).stream())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new RegionalPriceSummary(rows, userRepository.countMastersWithoutRegion(),
                baselineRepository.count(), computedAt);
    }
}
