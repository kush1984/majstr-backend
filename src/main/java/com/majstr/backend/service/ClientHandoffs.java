package com.majstr.backend.service;

import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.PdfRender;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PdfRenderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which masters put a document in front of a client — the ONE computation behind the admin funnel,
 * its by-source rows and the broadcast segment «склав кошторис, не віддав клієнту». Each answer is
 * a set of master ids with each master's referral source, so the aggregate takes {@code size()}, the
 * by-source report groups the same ids, and Σ over sources equals the aggregate by construction.
 * Masters only ({@code role = USER}), like every funnel step.
 */
@Component
@RequiredArgsConstructor
public class ClientHandoffs {

    private final EstimateRepository estimateRepository;
    private final PdfRenderRepository pdfRenderRepository;

    /**
     * An estimate of theirs reached SENT or SIGNED ({@link EstimateRepository#findOwnersWithEstimateIn}).
     *
     * <p><b>Changed 2026-11-09 (share-without-verification).</b> It used to be «owns a link row» over
     * both link tables — but the PWA mints a link when the share sheet OPENS, so a master who only
     * looked counted as having shared. SENT is set only once the link really left
     * ({@code ShareLinkService.markSent} / {@code sendByEmail}, {@code ProjectPortalService.markSent});
     * an act exists only on a signed estimate, so it adds nobody. Figures before 2026-10-18 are
     * slightly high (SENT was then set as the sheet opened); a sent estimate the master later deleted
     * drops out. The client preview (iteration-first-experience) changes no status, so it adds
     * nobody either.</p>
     */
    public Map<UUID, String> shared() {
        return toMap(estimateRepository.findOwnersWithEstimateIn(
                List.of(EstimateStatus.SENT, EstimateStatus.SIGNED)));
    }

    /** Made an estimate or act PDF in the app (V196, from 2026-11-09 — nothing before it is known). */
    public Map<UUID, String> pdfMade() {
        return toMap(pdfRenderRepository.findOwnersWithPdf(PdfRender.Reader.MASTER));
    }

    /** Whose client downloaded a PDF from one of the links. */
    public Map<UUID, String> clientDownloadedPdf() {
        return toMap(pdfRenderRepository.findOwnersWithPdf(PdfRender.Reader.CLIENT));
    }

    /**
     * «Віддали клієнту» = {@link #shared()} ∪ {@link #pdfMade()} — a union of id SETS, never a sum of
     * two counts: a master who did both is one master. A PDF is counted as handed over although we
     * cannot see it leave the phone: that is what masters do with it (Viber), and it is the half of
     * the handover the old funnel could not see at all.
     */
    public Map<UUID, String> handedToClient() {
        Map<UUID, String> owners = shared();
        owners.putAll(pdfMade());
        return owners;
    }

    private static Map<UUID, String> toMap(List<OwnerSource> rows) {
        Map<UUID, String> owners = new HashMap<>();
        for (OwnerSource row : rows) {
            owners.put(row.getOwnerId(), row.getSource());
        }
        return owners;
    }
}
