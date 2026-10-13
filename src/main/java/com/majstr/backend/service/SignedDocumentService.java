package com.majstr.backend.service;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.SignedDocument;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.SignedDocumentRepository;
import com.majstr.backend.service.SignedSnapshot.ActDoc;
import com.majstr.backend.service.SignedSnapshot.EstimateDoc;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A signature's freeze (V150, review B-76), and the way back from it.
 *
 * <p><b>At the signature</b> ({@link #freezeAct} / {@link #freezeEstimate}): every printed value is
 * read off the live entities into a {@link SignedSnapshot}, written in its canonical text, hashed,
 * and stored verbatim; the stamped PDF is rendered FROM that snapshot — not from the entities — so
 * the copy the client receives and every later render come from one source; the PDF is kept in
 * storage. The contractor's logo is copied for this signature, because replacing a logo deletes the
 * old file.</p>
 *
 * <p><b>Ever after</b> ({@link #act} / {@link #estimate}): the snapshot is rebuilt into DETACHED
 * entities — never persisted, never attached — and handed to the same renderers and view builders
 * a live document goes through. One renderer, two sources, no second rendering of the document to
 * drift from the first. A document signed before V150 has no snapshot and keeps rendering live.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignedDocumentService {

    private static final String PDF_PREFIX = "signed-docs";
    /** Under {@code logos/} on purpose: it is the one prefix {@code /api/files/**} serves. */
    private static final String LOGO_PREFIX = "logos/signed";

    private final SignedDocumentRepository repository;
    private final StorageService storage;
    private final StorageCleanup cleanup;
    private final FeatureGuard featureGuard;
    private final WorkActPdfService actPdf;
    private final EstimatePdfService estimatePdf;

    /** What a fresh signature hands back: the stamp, and the stamped PDF for the client's email. */
    public record Frozen(String docHash, byte[] stampedPdf) { }

    /** A signed act rebuilt from its snapshot — detached entities, ready for the renderers. */
    public record FrozenAct(User contractor, Project project, Client client, WorkAct act,
                            List<WorkActItem> items, List<WorkActReceipt> receipts,
                            Map<UUID, String> estimateNames,
                            WorkActPdfService.CumulativeReference cumulative,
                            String docHash, boolean branded) {
        public List<WorkActPdfService.ReceiptRow> receiptRows() {
            return receipts.stream().map(WorkActPdfService.ReceiptRow::from).toList();
        }

        public WorkActPdfService.PdfModel pdfModel() {
            return new WorkActPdfService.PdfModel(contractor, project, client, act, items, receiptRows(),
                    estimateNames, docHash, cumulative, branded);
        }
    }

    /** A signed estimate rebuilt from its snapshot. */
    public record FrozenEstimate(User contractor, Project project, Client client, Estimate estimate,
                                 List<EstimateItem> items, String docHash, boolean branded) {
        public EstimatePdfService.PdfModel pdfModel(List<byte[]> receiptImages) {
            return new EstimatePdfService.PdfModel(contractor, project, client, estimate, items,
                    receiptImages, docHash, branded);
        }
    }

    // ---- the freeze ---------------------------------------------------------------------------

    /**
     * Must run AFTER the signer fields are set and inside the signing transaction: the snapshot
     * certifies them, and a rollback must take the row and the stored blobs with it.
     */
    public Frozen freezeAct(WorkAct act, List<WorkActItem> items, List<WorkActReceipt> receipts,
                            Map<UUID, String> estimateNames,
                            WorkActPdfService.CumulativeReference cumulative) {
        Project project = act.getProject();
        User owner = project.getOwner();
        boolean branded = featureGuard.isEnabled(owner, Feature.BRANDED_PDF);
        LogoCopy logo = copyLogo(owner);
        Map<UUID, String> names = new LinkedHashMap<>();
        estimateNames.forEach((id, name) -> names.put(id, ClientSafeName.of(name)));
        ActDoc doc = new ActDoc(
                SignedSnapshot.SCHEMA,
                contractorOf(owner, branded, logo),
                projectOf(project),
                clientOf(project.getClient()),
                new SignedSnapshot.ActPart(act.getId(), act.getNumber(), act.getTitle(), act.getKind(),
                        act.getIssuedAt(), act.getPeriodFrom(), act.getPeriodTo(), act.getPlace(),
                        act.getContractRef(), act.getAdvanceOffset(), act.isShowMaterials(),
                        act.isShowCumulative(), act.isShowReceiptPhotos()),
                new SignedSnapshot.Signer(act.getSignedAt(), act.getSignerName(), act.getSignerPhone(),
                        act.isSignedOffline()),
                items.stream().map(i -> new SignedSnapshot.ActLine(i.getId(), i.getLineKind(),
                        i.getEstimateItemId(), i.getEstimateId(), i.getType(), i.getName(), i.getCategory(),
                        i.getUnit(), i.getUnitPrice(), i.getQuantity(), i.getLineTotal(),
                        i.getCumulativeBefore(), i.getSortOrder())).toList(),
                receipts.stream().map(r -> new SignedSnapshot.ActReceipt(r.getId(), r.getLabel(),
                        r.getIssuedAt(), r.getAmount(), r.getReturnedAmount(), r.getStorageKey(),
                        r.isItemized())).toList(),
                names,
                cumulative == null ? null
                        : new SignedSnapshot.Cumulative(cumulative.accepted(), cumulative.contracted()));
        String text = SignedSnapshot.canonical(doc);
        String hash = SignedSnapshot.sha256(text);
        SignedDocument row = repository.save(SignedDocument.builder()
                .id(UUID.randomUUID())
                .kind(SignedDocument.Kind.ACT)
                .workActId(act.getId())
                .schemaVersion(SignedSnapshot.SCHEMA)
                .snapshot(text)
                .docHash(hash)
                .logoKey(logo == null ? null : logo.key())
                .signedAt(act.getSignedAt())
                .build());
        act.setSignedDocumentId(row.getId());
        act.setDocHash(hash);
        byte[] pdf = render(() -> actPdf.render(rebuild(row, doc, act).pdfModel()));
        row.setPdfKey(storePdf(pdf));
        return new Frozen(hash, pdf);
    }

    /** Same contract as {@link #freezeAct}, for an estimate the client signs on the portal. */
    public Frozen freezeEstimate(Estimate estimate, List<EstimateItem> items) {
        Project project = estimate.getProject();
        User owner = project.getOwner();
        boolean branded = featureGuard.isEnabled(owner, Feature.BRANDED_PDF);
        LogoCopy logo = copyLogo(owner);
        EstimateDoc doc = new EstimateDoc(
                SignedSnapshot.SCHEMA,
                contractorOf(owner, branded, logo),
                projectOf(project),
                clientOf(project.getClient()),
                new SignedSnapshot.EstimatePart(estimate.getId(), estimate.getName(), estimate.getKind(),
                        estimate.getValidUntil(), estimate.getNotes(), estimate.getDepositAmount(),
                        estimate.getCreatedAt()),
                new SignedSnapshot.Signer(estimate.getSignedAt(), estimate.getSignerName(),
                        estimate.getSignerPhone(), false),
                items.stream().map(i -> new SignedSnapshot.EstimateLine(i.getId(), i.getType(), i.getName(),
                        i.getCategory(), i.getTrade(), i.getUnit(), i.getQuantity(), i.getUnitPrice(),
                        i.getLineTotal(), i.getPercentBaseKind(), i.getPercentBaseItemId(),
                        i.getBaseOriginLabel(), i.getSortOrder())).toList());
        String text = SignedSnapshot.canonical(doc);
        String hash = SignedSnapshot.sha256(text);
        SignedDocument row = repository.save(SignedDocument.builder()
                .id(UUID.randomUUID())
                .kind(SignedDocument.Kind.ESTIMATE)
                .estimateId(estimate.getId())
                .schemaVersion(SignedSnapshot.SCHEMA)
                .snapshot(text)
                .docHash(hash)
                .logoKey(logo == null ? null : logo.key())
                .signedAt(estimate.getSignedAt())
                .build());
        estimate.setSignedDocumentId(row.getId());
        byte[] pdf = render(() -> estimatePdf.render(rebuild(row, doc, estimate).pdfModel(List.of())));
        row.setPdfKey(storePdf(pdf));
        return new Frozen(hash, pdf);
    }

    // ---- the way back -------------------------------------------------------------------------

    /** The act as it was signed, or empty for an act with no snapshot (unsigned, or signed before V150). */
    public Optional<FrozenAct> act(WorkAct live) {
        if (live.getSignedDocumentId() == null || live.getStatus() != WorkActStatus.SIGNED) {
            return Optional.empty();
        }
        return repository.findById(live.getSignedDocumentId())
                .map(row -> rebuild(row, SignedSnapshot.read(row.getSnapshot(), ActDoc.class), live));
    }

    /** The estimate as it was signed, or empty — unsigned, reopened, or signed before V150. */
    public Optional<FrozenEstimate> estimate(Estimate live) {
        if (live.getSignedDocumentId() == null || live.getStatus() != EstimateStatus.SIGNED) {
            return Optional.empty();
        }
        return repository.findById(live.getSignedDocumentId())
                .map(row -> rebuild(row, SignedSnapshot.read(row.getSnapshot(), EstimateDoc.class), live));
    }

    // ---- rebuilding detached entities ----------------------------------------------------------

    private FrozenAct rebuild(SignedDocument row, ActDoc doc, WorkAct live) {
        Client client = client(doc.client());
        User contractor = contractor(doc.contractor(), row.getLogoKey(), live.getProject().getOwner().getId());
        Project project = project(doc.project(), contractor, client, live.getProject());
        SignedSnapshot.ActPart a = doc.act();
        WorkAct act = WorkAct.builder()
                .id(a.id())
                .userId(live.getUserId())
                .project(project)
                .number(a.number())
                .title(a.title())
                .kind(a.kind())
                .status(WorkActStatus.SIGNED)
                .issuedAt(a.issuedAt())
                .periodFrom(a.periodFrom())
                .periodTo(a.periodTo())
                .place(a.place())
                .contractRef(a.contractRef())
                .advanceOffset(a.advanceOffset())
                .showMaterials(a.showMaterials())
                .showCumulative(a.showCumulative())
                .showReceiptPhotos(a.showReceiptPhotos())
                .signedAt(doc.signer().signedAt())
                .signerName(doc.signer().name())
                .signerPhone(doc.signer().phone())
                .signedOffline(doc.signer().offline())
                .docHash(row.getDocHash())
                .signedDocumentId(row.getId())
                .addendumEstimateId(live.getAddendumEstimateId())
                .version(live.getVersion())
                .build();
        List<WorkActItem> items = doc.lines().stream().map(l -> WorkActItem.builder()
                .id(l.id()).workAct(act).lineKind(l.lineKind()).estimateItemId(l.estimateItemId())
                .estimateId(l.estimateId()).type(l.type()).name(l.name()).category(l.category())
                .unit(l.unit()).unitPrice(l.unitPrice()).quantity(l.quantity()).lineTotal(l.lineTotal())
                .cumulativeBefore(l.cumulativeBefore()).sortOrder(l.sortOrder()).build()).toList();
        List<WorkActReceipt> receipts = doc.receipts().stream().map(r -> WorkActReceipt.builder()
                .id(r.id()).workAct(act).label(r.label()).issuedAt(r.issuedAt()).amount(r.amount())
                .returnedAmount(r.returnedAmount()).storageKey(r.storageKey()).itemized(r.itemized())
                .build()).toList();
        WorkActPdfService.CumulativeReference cumulative = doc.cumulative() == null ? null
                : new WorkActPdfService.CumulativeReference(doc.cumulative().accepted(), doc.cumulative().contracted());
        return new FrozenAct(contractor, project, client, act, items, receipts,
                doc.estimateNames() == null ? Map.of() : doc.estimateNames(), cumulative,
                row.getDocHash(), doc.contractor().branded());
    }

    private FrozenEstimate rebuild(SignedDocument row, EstimateDoc doc, Estimate live) {
        Client client = client(doc.client());
        User contractor = contractor(doc.contractor(), row.getLogoKey(), live.getProject().getOwner().getId());
        Project project = project(doc.project(), contractor, client, live.getProject());
        SignedSnapshot.EstimatePart e = doc.estimate();
        Estimate estimate = Estimate.builder()
                .id(e.id())
                .project(project)
                .name(e.name())
                .kind(e.kind())
                .status(EstimateStatus.SIGNED)
                .validUntil(e.validUntil())
                .notes(e.notes())
                .depositAmount(e.depositAmount())
                .createdAt(e.createdAt())
                .signedAt(doc.signer().signedAt())
                .signerName(doc.signer().name())
                .signerPhone(doc.signer().phone())
                .signedDocumentId(row.getId())
                // Not printed, and they move after the signature on purpose (economy counting,
                // supersede, the portal pick) — read off the live row.
                .countInEconomy(live.isCountInEconomy())
                .portalVisible(live.isPortalVisible())
                .duplicatedFromId(live.getDuplicatedFromId())
                .supersededByEstimateId(live.getSupersededByEstimateId())
                .markupPercent(live.getMarkupPercent())
                .version(live.getVersion())
                .build();
        List<EstimateItem> items = doc.lines().stream().map(l -> EstimateItem.builder()
                .id(l.id()).estimate(estimate).type(l.type()).name(l.name()).category(l.category())
                .trade(l.trade()).unit(l.unit()).quantity(l.quantity()).unitPrice(l.unitPrice())
                .lineTotal(l.lineTotal()).percentBaseKind(l.percentBaseKind())
                .percentBaseItemId(l.percentBaseItemId()).baseOriginLabel(l.baseOriginLabel())
                .sortOrder(l.sortOrder()).build()).toList();
        return new FrozenEstimate(contractor, project, client, estimate, items, row.getDocHash(),
                doc.contractor().branded());
    }

    private static User contractor(SignedSnapshot.Contractor c, String logoKey, UUID ownerId) {
        return User.builder()
                .id(ownerId)
                .fullName(c.fullName())
                .companyName(c.companyName())
                .phone(c.phone())
                .legalName(c.legalName())
                .taxId(c.taxId())
                .legalAddress(c.legalAddress())
                .iban(c.iban())
                .bankName(c.bankName())
                .vatPayer(c.vatPayer())
                .vatId(c.vatId())
                .taxGroup(c.taxGroup())
                .taxRate(c.taxRate())
                .docCity(c.docCity())
                .logoUrl(logoKey)
                .build();
    }

    private static Client client(SignedSnapshot.ClientPart c) {
        if (c == null) {
            return null;
        }
        return Client.builder()
                .fullName(c.fullName())
                .phone(c.phone())
                .email(c.email())
                .clientType(c.clientType())
                .taxId(c.taxId())
                .legalName(c.legalName())
                .legalAddress(c.legalAddress())
                .signatoryTitle(c.signatoryTitle())
                .signatoryName(c.signatoryName())
                .build();
    }

    private static Project project(SignedSnapshot.ProjectPart p, User contractor, Client client, Project live) {
        return Project.builder()
                .id(p.id())
                .owner(contractor)
                .client(client)
                .name(p.name())
                .address(p.address())
                .status(live.getStatus())
                .build();
    }

    // ---- snapshot parts ------------------------------------------------------------------------

    private static SignedSnapshot.Contractor contractorOf(User u, boolean branded, LogoCopy logo) {
        return new SignedSnapshot.Contractor(u.getFullName(), u.getCompanyName(), u.getPhone(),
                u.getLegalName(), u.getTaxId(), u.getLegalAddress(), u.getIban(), u.getBankName(),
                u.isVatPayer(), u.getVatId(), u.getTaxGroup(), u.getTaxRate(), u.getDocCity(), branded,
                logo == null ? null : logo.sha256());
    }

    private static SignedSnapshot.ProjectPart projectOf(Project p) {
        return new SignedSnapshot.ProjectPart(p.getId(), p.getName(), p.getAddress());
    }

    private static SignedSnapshot.ClientPart clientOf(Client c) {
        if (c == null) {
            return null;
        }
        return new SignedSnapshot.ClientPart(c.getFullName(), c.getPhone(), c.getEmail(), c.getClientType(),
                c.getTaxId(), c.getLegalName(), c.getLegalAddress(), c.getSignatoryTitle(),
                c.getSignatoryName());
    }

    // ---- storage -------------------------------------------------------------------------------

    private record LogoCopy(String key, String sha256) { }

    /**
     * This signature's own copy of the logo. Fail-soft: a logo that cannot be read or stored costs
     * the document its logo, never the client his signature.
     */
    private LogoCopy copyLogo(User owner) {
        String key = owner.getLogoUrl();
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            Optional<InputStream> stream = storage.open(key);
            if (stream.isEmpty()) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = stream.get()) {
                bytes = in.readAllBytes();
            }
            ImageContentTypeDetector.ImageKind kind = ImageContentTypeDetector.detect(bytes);
            StoredObject stored = storage.store(new ByteArrayInputStream(bytes), bytes.length, LOGO_PREFIX,
                    kind.extension, kind.contentType);
            cleanup.onRollback(stored.key());
            return new LogoCopy(stored.key(), SignedSnapshot.sha256(bytes));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not copy the logo {} for a signed document: {}", key, e.getMessage());
            return null;
        }
    }

    /** The stamped PDF, kept as the client received it. Fail-soft: the snapshot is the record. */
    private String storePdf(byte[] pdf) {
        try {
            StoredObject stored = storage.store(new ByteArrayInputStream(pdf), pdf.length, PDF_PREFIX, "pdf",
                    "application/pdf");
            cleanup.onRollback(stored.key());
            return stored.key();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not store a signed document's PDF: {}", e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface Render {
        byte[] get() throws Exception;
    }

    /** A render failure fails the signature — as it always did — but as an unchecked exception, so
     *  the estimate's portal sign (a plain {@code @Transactional}) rolls back too. */
    private static byte[] render(Render render) {
        try {
            return render.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not render the signed document", e);
        }
    }
}
