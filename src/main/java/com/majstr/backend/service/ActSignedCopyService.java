package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a signature leaves behind, shared by BOTH sign paths â the public portal and the offline
 * one (review fix: offline signing used to produce neither, so an offline-signed act had no tamper
 * stamp and the client no independent copy):
 *
 * <ul>
 *   <li>{@link #computeDocHash} â SHA-256 of the CANONICAL PDF (no doc-hash footer, no live
 *       Â«ÐÐÐÐÐÐÐÐÐÂ» block, so a later signing on the object never invalidates this act's stamp);</li>
 *   <li>{@link #emailClientCopy} â the stamped PDF mailed to the client, fail-soft: the signature
 *       already landed, the emailed copy is a bonus evidence trail.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ActSignedCopyService {

    private final WorkActPdfService pdfService;
    private final ActCumulativeCalculator cumulativeCalculator;
    private final EstimateRepository estimateRepository;
    private final EmailService emailService;

    /** Must be called AFTER the signer fields are set â they are part of what the hash certifies. */
    String computeDocHash(WorkAct act, java.util.List<WorkActItem> items,
                          java.util.List<WorkActPdfService.ReceiptRow> receipts)
            throws IOException, DocumentException {
        byte[] canonical = pdfService.render(model(act, items, receipts, null, null));
        return sha256Hex(canonical);
    }

    void emailClientCopy(WorkAct act, java.util.List<WorkActItem> items,
                         java.util.List<WorkActPdfService.ReceiptRow> receipts) {
        Client client = act.getProject().getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            return;
        }
        try {
            byte[] stamped = pdfService.render(model(act, items, receipts, act.getDocHash(),
                    cumulativeCalculator.forDownload(act, items, receiptsTotal(receipts))));
            emailService.sendSignedActCopyEmail(client.getEmail(), client.getFullName(),
                    contractorName(act.getProject().getOwner()), act.getNumber(), stamped);
        } catch (Exception e) {
            // Fail-soft: the signature already landed; the emailed copy is a bonus trail.
        }
    }

    private WorkActPdfService.PdfModel model(WorkAct act, java.util.List<WorkActItem> items,
                                             java.util.List<WorkActPdfService.ReceiptRow> receipts,
                                             String docHash, WorkActPdfService.CumulativeReference cumulative) {
        Project project = act.getProject();
        Map<UUID, String> names = new HashMap<>();
        items.stream().map(WorkActItem::getEstimateId).filter(Objects::nonNull).distinct().forEach(id ->
                estimateRepository.findById(id).ifPresent(e ->
                        names.put(id, e.getName() == null || e.getName().isBlank() ? "ÐÐ¾ÑÑÐ¾ÑÐ¸Ñ" : e.getName().trim())));
        return new WorkActPdfService.PdfModel(
                project.getOwner(), project, project.getClient(), act, items, receipts, names, docHash,
                cumulative);
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

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
