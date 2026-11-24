package com.majstr.backend.service;

import com.majstr.backend.dto.ReopenBlock;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The one answer to «can this signed estimate be reopened, and if not, why» — read by the reopen
 * itself AND sent with the estimate (editor, economy panel), so what the app shows before the tap
 * is the rule the server applies after it (owner, 2026-11-24).
 *
 * <p>Any act that is not REJECTED stands in the way (B-59: a SENT act is on the client's phone, a
 * SIGNED one froze lines that keep counting). The strongest one is named: SIGNED → a new estimate;
 * SENT → back to draft and delete; only DRAFT → delete.</p>
 */
@Component
@RequiredArgsConstructor
public class ReopenGuard {

    private final WorkActItemRepository workActItemRepository;
    private final WorkActRepository workActRepository;

    /** Null when the estimate is not SIGNED or nothing stands in the way. */
    public ReopenBlock blockOf(Estimate estimate) {
        if (estimate.getStatus() != EstimateStatus.SIGNED) {
            return null;
        }
        return blockOfSigned(estimate.getId(), estimate.getKind());
    }

    /** {@link #blockOf} for an estimate already known to be SIGNED — the economy's panel rows. */
    public ReopenBlock blockOfSigned(UUID estimateId, EstimateKind kind) {
        if (kind == EstimateKind.ADDENDUM) {
            return new ReopenBlock(ReopenBlock.Reason.ADDENDUM, List.of());
        }
        List<Object[]> acts = workActItemRepository.findLiveActsOnEstimate(estimateId);
        List<String> signed = numbers(acts, WorkActStatus.SIGNED);
        if (!signed.isEmpty()) {
            return new ReopenBlock(ReopenBlock.Reason.SIGNED_ACT, signed);
        }
        if (workActRepository.existsByAddendumEstimateIdAndStatus(estimateId, WorkActStatus.SIGNED)) {
            return new ReopenBlock(ReopenBlock.Reason.SIGNED_ACT, List.of());
        }
        if (acts.isEmpty()) {
            return null;
        }
        // Every live act has to go, the drafts beside a sent one too — name them all.
        List<String> all = acts.stream().map(row -> (String) row[0]).distinct()
                .sorted(Comparator.naturalOrder()).toList();
        boolean sent = !numbers(acts, WorkActStatus.SENT).isEmpty();
        return new ReopenBlock(sent ? ReopenBlock.Reason.SENT_ACT : ReopenBlock.Reason.DRAFT_ACT, all);
    }

    /** Refuses the reopen with the code and message of {@link #blockOf}. */
    public void requireReopenable(Estimate estimate) {
        ReopenBlock block = blockOf(estimate);
        if (block == null) {
            return;
        }
        throw switch (block.reason()) {
            case ADDENDUM -> new WorkActConflictException("error.estimate.addendum-readonly", "ESTIMATE_ADDENDUM_LOCKED");
            case DRAFT_ACT -> new WorkActConflictException("error.estimate.reopen-has-draft-act", "ESTIMATE_HAS_DRAFT_ACT");
            case SENT_ACT -> new WorkActConflictException("error.estimate.reopen-has-sent-act", "ESTIMATE_HAS_SENT_ACT");
            case SIGNED_ACT -> new WorkActConflictException("error.estimate.reopen-has-acts", "ESTIMATE_HAS_SIGNED_ACTS");
        };
    }

    private static List<String> numbers(List<Object[]> acts, WorkActStatus status) {
        return acts.stream().filter(row -> row[1] == status).map(row -> (String) row[0])
                .distinct().sorted(Comparator.naturalOrder()).toList();
    }
}
