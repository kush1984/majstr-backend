package com.majstr.backend.service;

import com.majstr.backend.dto.TileLayoutRequest;
import com.majstr.backend.entity.EstimateTileLayout;
import com.majstr.backend.repository.EstimateTileLayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stored tile layouts (V203). Repositories ONLY, like {@link MaterialParamService}: ownership is
 * checked by the caller ({@link MaterialCalculatorService}), and {@link EstimateService} calls back in
 * on a unit change and on a duplicate — a dependency on either would be a constructor cycle.
 */
@Service
@RequiredArgsConstructor
public class TileLayoutService {

    private final EstimateTileLayoutRepository repository;

    @Transactional(readOnly = true)
    public List<EstimateTileLayout> load(UUID estimateId) {
        return repository.findByEstimateId(estimateId);
    }

    /** Insert or replace the line's layout — idempotent, so an outbox replay lands on the same row. */
    @Transactional
    public EstimateTileLayout save(UUID estimateId, UUID estimateItemId, TileLayoutRequest req) {
        repository.lockItem(estimateItemId);
        EstimateTileLayout row = repository.findByEstimateItemId(estimateItemId)
                .orElseGet(() -> EstimateTileLayout.builder()
                        .estimateId(estimateId)
                        .estimateItemId(estimateItemId)
                        .build());
        TileFormats.Format format = TileFormats.Format.of(req.tileWidthMm(), req.tileHeightMm());
        row.setTileShortMm(format.shortMm());
        row.setTileLongMm(format.longMm());
        row.setLaidArea(req.laidArea().setScale(3, RoundingMode.HALF_UP));
        row.setMinTiles(req.minTiles());
        row.setMaxTiles(req.maxTiles());
        row.setSpare(req.spare());
        row.setParams(req.params());
        if (req.measurementsDeclined() != null) {
            row.setMeasurementsDeclined(req.measurementsDeclined());
        }
        return repository.save(row);
    }

    /** True when there was one to delete. */
    @Transactional
    public boolean delete(UUID estimateItemId) {
        return repository.deleteByEstimateItemId(estimateItemId) > 0;
    }

    /**
     * The line is no longer measured in m² — a layout of tiles cannot describe it. A RENAME is not
     * this: it only pauses the layout on the read path, so «перерахувати» still starts from it.
     */
    @Transactional
    public void clearForItem(UUID estimateItemId) {
        repository.deleteByEstimateItemId(estimateItemId);
    }

    /** Carry the layouts into a duplicate — the same walls, as {@link MaterialParamService#copyToDuplicate}. */
    @Transactional
    public void copyToDuplicate(UUID sourceEstimateId, UUID copyEstimateId, Map<UUID, UUID> itemByOriginalId) {
        List<EstimateTileLayout> copies = new ArrayList<>();
        for (EstimateTileLayout l : repository.findByEstimateId(sourceEstimateId)) {
            UUID itemId = itemByOriginalId.get(l.getEstimateItemId());
            if (itemId == null) {
                continue;
            }
            copies.add(EstimateTileLayout.builder()
                    .estimateId(copyEstimateId)
                    .estimateItemId(itemId)
                    .tileShortMm(l.getTileShortMm())
                    .tileLongMm(l.getTileLongMm())
                    .laidArea(l.getLaidArea())
                    .minTiles(l.getMinTiles())
                    .maxTiles(l.getMaxTiles())
                    .spare(l.getSpare())
                    .params(l.getParams())
                    .measurementsDeclined(l.isMeasurementsDeclined())
                    .build());
        }
        repository.saveAll(copies);
    }
}
