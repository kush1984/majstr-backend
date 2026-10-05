package com.majstr.backend.service;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * «Підсумковий акт» means the last one — and once the client has signed it, nothing else may still
 * become a document on that object (review B-62).
 *
 * <p>{@link WorkActCreator} has always refused a second act after a FINAL one, so the rule looked
 * closed. It was not: a REJECTED act is not an OPEN act, so the object could carry a rejected
 * interim act, get a FINAL act created, signed and paid — and the master could then move the
 * rejected one back to DRAFT and sign it too. The object ends up with an act dated after its own
 * closing act, and (before {@link ActLineBinder} capped quantities) with 120 м² closed on a 100 м²
 * position.</p>
 *
 * <p>The guard therefore sits on every door that can still turn a non-signed act into a signed one —
 * the move back to DRAFT, the publish to SENT and the offline signature. With the DRAFT move
 * refused, the other two are unreachable in practice; they carry the check anyway, for the same
 * reason {@link ActLineBinder#requireStillValid} runs at every door: the answer has to be the same
 * wherever it is asked, and a future path that forgets to ask is the bug this class exists to
 * prevent. The portal's own sign is deliberately NOT guarded — a SENT act cannot coexist with a
 * signed FINAL once publish refuses it, and an error the CLIENT cannot act on is worse than none.</p>
 */
@Component
@RequiredArgsConstructor
class ActFinalGuard {

    private final WorkActRepository workActRepository;

    /** Refuses when a SIGNED FINAL act other than this one already closed the object. */
    void requireObjectNotClosed(WorkAct act) {
        if (workActRepository.existsByProjectIdAndKindAndStatusAndIdNot(
                act.getProject().getId(), WorkActKind.FINAL, WorkActStatus.SIGNED, act.getId())) {
            throw new WorkActConflictException("error.work-act.final-signed", "WORK_ACT_FINAL_SIGNED");
        }
    }
}
