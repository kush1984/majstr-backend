package com.majstr.backend.service;

import com.majstr.backend.dto.FiscalIdentity;
import com.majstr.backend.dto.ReceiptDuplicateRef;
import com.majstr.backend.entity.ProjectReceipt;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.ProjectReceiptRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answers «is this very paper already filed somewhere else on this object?» across BOTH receipt
 * tables (review item B-04).
 *
 * <p>Until now each table could only see itself: V129 flagged a duplicate among the object's own
 * receipts, and an act receipt had no printed identity at all. So the one case that actually costs
 * money — the same slip photographed at the till AND attached to an act — was the only one nobody
 * warned about. V134 gave {@code work_act_receipt} the same two columns, and this is the component
 * that reads them together.</p>
 *
 * <p><b>The key is the printed fiscal identity alone</b> ({@code fn} + {@code id}), never the amount:
 * a partial return (V115) legitimately makes the two rows disagree about money while they remain the
 * same piece of paper. That is also why the identity is enough to act on WITHOUT asking the master —
 * the tax code on the slip is not a guess.</p>
 *
 * <p>Two rules decide which row of a pair gets the warning, and both exist so the answer is stable:</p>
 * <ul>
 *   <li><b>the earliest row wins</b> — the warning lands on the paper filed SECOND, keeping V129's
 *       behaviour for two object receipts;</li>
 *   <li><b>the same table beats the other one</b> — an object receipt duplicated on the object points
 *       at its object twin, not across, so the master is sent to the list he is already looking at.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ReceiptIdentityIndex {

    private final ProjectReceiptRepository projectReceipts;
    private final WorkActReceiptRepository actReceipts;
    private final WorkActRepository acts;

    /**
     * Load one object's identified papers — two queries, whatever the list length, so rendering a
     * hundred receipts stays two queries and not two hundred.
     *
     * @param billedScope receipts whose {@code billedOnActId} must be resolvable to an act NUMBER;
     *                    pass the rows about to be rendered, or an empty list on the act side
     */
    Twins forProject(UUID projectId, Collection<ProjectReceipt> billedScope) {
        List<ProjectReceipt> objectRows = projectReceipts.findIdentifiedByProjectId(projectId);
        List<WorkActReceipt> actRows = actReceipts.findIdentifiedByProjectId(projectId);
        Map<UUID, String> numbers = new HashMap<>();
        for (WorkActReceipt r : actRows) {
            numbers.put(r.getWorkAct().getId(), r.getWorkAct().getNumber());
        }
        Set<UUID> missing = new HashSet<>();
        for (ProjectReceipt r : billedScope) {
            if (r.getBilledOnActId() != null && !numbers.containsKey(r.getBilledOnActId())) {
                missing.add(r.getBilledOnActId());
            }
        }
        if (!missing.isEmpty()) {
            for (WorkAct a : acts.findAllById(missing)) {
                numbers.put(a.getId(), a.getNumber());
            }
        }
        return new Twins(objectRows, actRows, numbers);
    }

    /** A project's identified papers, already loaded — every lookup below is pure map work. */
    record Twins(List<ProjectReceipt> objectRows, List<WorkActReceipt> actRows,
                 Map<UUID, String> actNumbers) {

        /** Where the twin of an OBJECT receipt is, or null — the ordinary answer. */
        ReceiptDuplicateRef forObject(ProjectReceipt receipt) {
            String key = keyOf(receipt.getFiscalFn(), receipt.getFiscalId());
            if (key == null) {
                return null; // a hand-written товарний чек identifies nothing, and that gap is real
            }
            for (ProjectReceipt other : objectRows) {
                if (other.getId().equals(receipt.getId())) {
                    break; // reached itself first: this IS the earliest copy, so it carries no warning
                }
                if (key.equals(keyOf(other.getFiscalFn(), other.getFiscalId()))) {
                    return ReceiptDuplicateRef.object(other.getId(), other.getLabel());
                }
            }
            for (WorkActReceipt other : actRows) {
                if (key.equals(keyOf(other.getFiscalFn(), other.getFiscalId()))) {
                    return ReceiptDuplicateRef.act(other.getId(), other.getLabel(),
                            other.getWorkAct().getNumber());
                }
            }
            return null;
        }

        /** Where the twin of an ACT receipt is, or null. Mirror image of {@link #forObject}. */
        ReceiptDuplicateRef forAct(WorkActReceipt receipt) {
            String key = keyOf(receipt.getFiscalFn(), receipt.getFiscalId());
            if (key == null) {
                return null;
            }
            for (WorkActReceipt other : actRows) {
                if (other.getId().equals(receipt.getId())) {
                    break;
                }
                if (key.equals(keyOf(other.getFiscalFn(), other.getFiscalId()))) {
                    return ReceiptDuplicateRef.act(other.getId(), other.getLabel(),
                            other.getWorkAct().getNumber());
                }
            }
            for (ProjectReceipt other : objectRows) {
                if (key.equals(keyOf(other.getFiscalFn(), other.getFiscalId()))) {
                    return ReceiptDuplicateRef.object(other.getId(), other.getLabel());
                }
            }
            return null;
        }

        /** The display number of the act a receipt was billed on, or null if it was not. */
        String actNumber(UUID actId) {
            return actId == null ? null : actNumbers.get(actId);
        }

        private static String keyOf(String fn, String id) {
            // Not inlined as `fn + "|" + id`: a legacy row saved with a BLANK identity (B-21) would
            // otherwise key as "|" and become the twin of every other blank row on the object.
            return FiscalIdentity.key(fn, id);
        }
    }
}
