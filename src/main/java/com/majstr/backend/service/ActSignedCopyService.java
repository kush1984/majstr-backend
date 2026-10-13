package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a signature leaves behind, shared by BOTH sign paths — the public portal and the offline
 * one (review fix: offline signing used to produce neither, so an offline-signed act had no tamper
 * stamp and the client no independent copy):
 *
 * <ul>
 *   <li>the FREEZE ({@link SignedDocumentService#freezeAct}, V150, B-76) — every printed value
 *       snapshotted, {@code doc_hash} = SHA-256 of that snapshot's canonical text, the stamped PDF
 *       rendered from it and kept in storage;</li>
 *   <li>{@link #emailClientCopy} — that same stamped PDF mailed to the client, fail-soft: the
 *       signature already landed, the emailed copy is a bonus evidence trail.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ActSignedCopyService {

    private final ActCumulativeCalculator cumulativeCalculator;
    private final EstimateRepository estimateRepository;
    private final EmailService emailService;
    private final AfterCommit afterCommit;
    private final SignedDocumentService signedDocuments;

    /**
     * Must be called AFTER the signer fields are set — they are part of what the hash certifies.
     * Sets {@code doc_hash} and {@code signed_document_id} on the act and schedules the client's
     * copy for after the commit.
     */
    void freezeAndSend(WorkAct act, List<WorkActItem> items, List<WorkActReceipt> receipts) {
        List<WorkActPdfService.ReceiptRow> rows = receipts.stream().map(WorkActPdfService.ReceiptRow::from).toList();
        // «ДОВІДКОВО» as of this signature (B-94) is part of what is frozen: it used to be left out
        // of the hash only because the hash was taken over a live render.
        SignedDocumentService.Frozen frozen = signedDocuments.freezeAct(act, items, receipts,
                estimateNames(items), cumulativeCalculator.forDownload(act, items, receiptsTotal(rows)));
        emailClientCopy(act, frozen.stampedPdf());
    }

    /**
     * The stamped copy for the client, MAILED after the commit (review B-81): sending is
     * irreversible, so it must not happen until the signing transaction has actually committed —
     * a signature that lost its optimistic lock used to put a «signed» PDF in the client's inbox.
     * Everything the send needs is copied out to locals first: afterwards the entities are detached.
     */
    private void emailClientCopy(WorkAct act, byte[] stamped) {
        Client client = act.getProject().getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            return;
        }
        String email = client.getEmail();
        String clientName = client.getFullName();
        String contractor = contractorName(act.getProject().getOwner());
        String number = act.getNumber();
        afterCommit.run(() -> {
            try {
                emailService.sendSignedActCopyEmail(email, clientName, contractor, number, stamped);
            } catch (RuntimeException e) {
                // Fail-soft: see the javadoc.
            }
        });
    }

    private Map<UUID, String> estimateNames(List<WorkActItem> items) {
        Map<UUID, String> names = new HashMap<>();
        items.stream().map(WorkActItem::getEstimateId).filter(Objects::nonNull).distinct().forEach(id ->
                estimateRepository.findById(id).ifPresent(e ->
                        names.put(id, e.getName() == null || e.getName().isBlank() ? "Кошторис" : e.getName().trim())));
        return names;
    }

    /**
     * The act's own receipts as the «ДОВІДКОВО» block counts them, mirroring
     * {@code WorkActReceiptRepository.sumByWorkActId} exactly: <b>billed</b> amounts (paid less
     * returned, V115) and <b>itemized rows excluded</b> — their money is already in the act lines.
     * Summing gross over every row, as this used to, double-counted an itemized receipt and billed
     * back a partial return. Package-private so the arithmetic is unit-testable on its own; it is
     * dead weight for a SIGNED act (the calculator ignores own receipts there) and must still be
     * right, because that is the only reason the divergence went unnoticed.
     */
    static java.math.BigDecimal receiptsTotal(java.util.List<WorkActPdfService.ReceiptRow> receipts) {
        return receipts.stream()
                .filter(r -> !r.itemized())
                .map(WorkActPdfService.ReceiptRow::billedAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private static String contractorName(User owner) {
        if (owner.getCompanyName() != null && !owner.getCompanyName().isBlank()) return owner.getCompanyName().trim();
        if (owner.getLegalName() != null && !owner.getLegalName().isBlank()) return owner.getLegalName().trim();
        return owner.getFullName();
    }
}
