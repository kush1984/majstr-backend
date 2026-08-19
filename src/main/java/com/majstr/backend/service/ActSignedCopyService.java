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
 * What a signature leaves behind, shared by BOTH sign paths — the public portal and the offline
 * one (review fix: offline signing used to produce neither, so an offline-signed act had no tamper
 * stamp and the client no independent copy):
 *
 * <ul>
 *   <li>{@link #computeDocHash} — SHA-256 of the CANONICAL PDF (no doc-hash footer, no live
 *       «ДОВІДКОВО» block, so a later signing on the object never invalidates this act's stamp);</li>
 *   <li>{@link #emailClientCopy} — the stamped PDF mailed to the client, fail-soft: the signature
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

    /** Must be called AFTER the signer fields are set — they are part of what the hash certifies. */
    String computeDocHash(WorkAct act, java.util.List<WorkActItem> items)
            throws IOException, DocumentException {
        byte[] canonical = pdfService.render(model(act, items, null, null));
        return sha256Hex(canonical);
    }

    void emailClientCopy(WorkAct act, java.util.List<WorkActItem> items) {
        Client client = act.getProject().getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            return;
        }
        try {
            byte[] stamped = pdfService.render(model(act, items, act.getDocHash(),
                    cumulativeCalculator.forDownload(act, items)));
            emailService.sendSignedActCopyEmail(client.getEmail(), client.getFullName(),
                    contractorName(act.getProject().getOwner()), act.getNumber(), stamped);
        } catch (Exception e) {
            // Fail-soft: the signature already landed; the emailed copy is a bonus trail.
        }
    }

    private WorkActPdfService.PdfModel model(WorkAct act, java.util.List<WorkActItem> items,
                                             String docHash, WorkActPdfService.CumulativeReference cumulative) {
        Project project = act.getProject();
        Map<UUID, String> names = new HashMap<>();
        items.stream().map(WorkActItem::getEstimateId).filter(Objects::nonNull).distinct().forEach(id ->
                estimateRepository.findById(id).ifPresent(e ->
                        names.put(id, e.getName() == null || e.getName().isBlank() ? "Кошторис" : e.getName().trim())));
        return new WorkActPdfService.PdfModel(
                project.getOwner(), project, project.getClient(), act, items, names, docHash, cumulative);
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
