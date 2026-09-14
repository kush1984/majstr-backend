package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActReceiptResponse;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional halves of {@link WorkActReceiptService#add}, in their OWN bean — so a lost race
 * on the client-supplied primary key rolls back cleanly and the caller can answer with the receipt
 * that won instead of a 500. Exactly the reason {@link WorkActCreator} exists: the recovery has to
 * read the row back AFTER the failed insert, which the poisoned transaction cannot do, and
 * self-invocation would never cross Spring's proxy.
 */
@Component
@RequiredArgsConstructor
class WorkActReceiptCreator {

    private final WorkActReceiptRepository receiptRepository;
    private final WorkActRepository workActRepository;
    private final WorkActService actService;

    /**
     * What the non-transactional caller needs before it touches storage: the act's project (for the
     * optional gallery copy) and the already-landed receipt, if this is a replay.
     */
    record Prepared(UUID projectId, WorkActReceiptResponse replay) { }

    /**
     * Owner check, then the replay lookup, and only then the immutability guard.
     *
     * <p>That order is the fix: a receipt that already landed is not a write. A queued offline create
     * replayed after the act was signed — the ordinary case, since signing is exactly what the master
     * does next — used to be refused 409 {@code WORK_ACT_SIGNED}, and the outbox retried that op
     * forever. The owner check stays first: a replay must be as owner-scoped as the create it stands
     * in for.</p>
     */
    @Transactional(readOnly = true)
    public Prepared prepare(UUID actId, UUID ownerId, UUID requestedId) {
        WorkAct act = actService.loadOwned(actId, ownerId);
        UUID projectId = act.getProject().getId();
        Optional<WorkActReceiptResponse> landed = find(requestedId, actId);
        if (landed.isPresent()) {
            return new Prepared(projectId, landed.get());
        }
        WorkActService.requireNotSigned(act);
        return new Prepared(projectId, null);
    }

    /** Re-read a replayed create's receipt in a FRESH transaction, after a duplicate-key failure. */
    @Transactional(readOnly = true)
    public Optional<WorkActReceiptResponse> replay(UUID requestedId, UUID actId) {
        return find(requestedId, actId);
    }

    /**
     * One create attempt in its own transaction. {@code saveAndFlush} so a duplicate primary key
     * surfaces HERE rather than on commit, where the caller could no longer tell it apart.
     */
    @Transactional
    public WorkActReceiptResponse attempt(UUID actId, UUID requestedId, String label,
                                         BigDecimal amount, LocalDate issuedAt, String storageKey,
                                         int sortOrder) {
        WorkActReceipt receipt = WorkActReceipt.builder()
                .id(requestedId)
                .workAct(workActRepository.getReferenceById(actId))
                .label(label)
                .amount(amount)
                .issuedAt(issuedAt)
                .storageKey(storageKey)
                .sortOrder(sortOrder)
                .build();
        return WorkActReceiptResponse.from(receiptRepository.saveAndFlush(receipt));
    }

    private Optional<WorkActReceiptResponse> find(UUID requestedId, UUID actId) {
        if (requestedId == null) {
            return Optional.empty();
        }
        return receiptRepository.findById(requestedId).map(r -> {
            // Bound to THIS act, which is already owner-checked — a replay naming a foreign act is a
            // 404, never a peek at somebody else's receipt.
            if (!r.getWorkAct().getId().equals(actId)) {
                throw new ResourceNotFoundException("Receipt not found: " + requestedId);
            }
            return WorkActReceiptResponse.from(r);
        });
    }
}
