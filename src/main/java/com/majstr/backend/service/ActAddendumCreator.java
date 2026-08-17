package com.majstr.backend.service;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls an act's ADDITIONAL (off-estimate) positions into a SIGNED, counted, non-shared «Додаткові
 * роботи до акта № N» estimate (kind ADDENDUM) when the act is signed. Shared by BOTH sign paths —
 * the offline {@link WorkActService#signOffline} and the client-facing {@link
 * PublicActPortalService#sign} — so «За договором» absorbs the extra works no matter how the act was
 * signed, and «Прийнято актами» can never exceed it (acts-fix; the portal path used to skip this).
 */
@Component
@RequiredArgsConstructor
class ActAddendumCreator {

    private final WorkActItemRepository itemRepository;
    private final EstimateRepository estimateRepository;
    private final EstimateItemRepository estimateItemRepository;

    /** Must run in the SAME transaction as the sign, BEFORE the act is stamped SIGNED (there is no
     *  per-row immutability guard, but keeping it pre-SIGNED matches the offline path's ordering). */
    void createIfNeeded(WorkAct act) {
        List<WorkActItem> additional = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId())
                .stream().filter(i -> i.getEstimateItemId() == null).toList();
        if (additional.isEmpty()) {
            return;
        }
        Estimate addendum = estimateRepository.save(Estimate.builder()
                .project(act.getProject())
                .name("Додаткові роботи до акта № " + act.getNumber())
                .status(EstimateStatus.SIGNED)
                .kind(EstimateKind.ADDENDUM)
                .countInEconomy(true)
                .portalVisible(false)
                .economyVisible(false)
                .signedAt(Instant.now())
                .build());
        List<EstimateItem> lines = new ArrayList<>();
        int sort = 0;
        for (WorkActItem a : additional) {
            lines.add(EstimateItem.builder()
                    .estimate(addendum)
                    .type(a.getType())
                    .name(a.getName())
                    .category(a.getCategory())
                    .unit(a.getUnit())
                    .quantity(a.getQuantity())
                    .unitPrice(a.getUnitPrice())
                    .sortOrder(sort++)
                    .build());
        }
        EstimateMath.recalculate(lines); // fills line_total
        estimateItemRepository.saveAll(lines);
        act.setAddendumEstimateId(addendum.getId());
    }
}
