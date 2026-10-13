package com.majstr.backend.integration;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.majstr.backend.dto.PublicActView;
import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.SignedDocument;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.SignedDocumentRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.PublicActPortalService;
import com.majstr.backend.service.PublicEstimateService;
import com.majstr.backend.service.SignedSnapshot;
import com.majstr.backend.service.WorkActService;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review B-76: what a signature certifies is the document as it stood, and it stays that way.
 *
 * <p>The promise is tested the way it is broken: sign, then change what a live render reads — the
 * master's company and IBAN, the client's name, the object's name — and read the document again.
 * Every value is ASCII on purpose: the PDF text extractor reads Latin text reliably, and the
 * assertion is about WHICH value is printed, not how Cyrillic is encoded.</p>
 */
class SignedDocumentIntegrationTest extends IntegrationTestBase {

    private static final String OLD_COMPANY = "OLDCO";
    private static final String NEW_COMPANY = "NEWCO";
    private static final String OLD_IBAN = "UA213223130000026007233566001";
    private static final String NEW_IBAN = "UA903052992990004149123456789";

    @Autowired WorkActService workActService;
    @Autowired PublicActPortalService actPortal;
    @Autowired PublicEstimateService estimatePortal;
    @Autowired EstimateService estimateService;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository workActRepository;
    @Autowired SignedDocumentRepository signedDocumentRepository;
    @Autowired ProjectShareLinkRepository projectShareLinkRepository;
    @Autowired EstimateShareLinkRepository estimateShareLinkRepository;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;

    // ---- acts ----------------------------------------------------------------------------------

    @Test
    void aSignedAct_printsWhatWasSigned_afterTheRequisitesTheClientAndTheObjectChange() throws Exception {
        Fixture f = fixture();
        workActService.signOffline(f.actId, new WorkActSignOfflineRequest("Client"), f.ownerId);

        WorkAct act = workActRepository.findById(f.actId).orElseThrow();
        SignedDocument doc = signedDocumentRepository.findById(act.getSignedDocumentId()).orElseThrow();
        // The stamp is reproducible: anyone holding the stored text recomputes it.
        assertThat(act.getDocHash()).isEqualTo(doc.getDocHash())
                .isEqualTo(SignedSnapshot.sha256(doc.getSnapshot()));
        assertThat(doc.getPdfKey()).isNotNull();
        assertThat(stored(doc.getPdfKey())).startsWith("%PDF");

        rewriteEverythingALiveRenderReads(f);

        String pdf = text(workActService.renderPdf(f.actId, f.ownerId));
        assertThat(pdf).contains(OLD_COMPANY, OLD_IBAN, "Old Object", doc.getDocHash())
                .doesNotContain(NEW_COMPANY, NEW_IBAN, "New Object");

        String token = actLink(act);
        PublicActView view = actPortal.view(token);
        assertThat(view.contractorName()).isEqualTo(OLD_COMPANY);
        assertThat(view.objectName()).isEqualTo("Old Object");
        assertThat(view.clientName()).isEqualTo("Old Client");
        assertThat(text(actPortal.pdf(token))).contains(OLD_IBAN).doesNotContain(NEW_IBAN);
    }

    @Test
    void anActSignedBeforeTheSnapshots_keepsRenderingLive() throws Exception {
        Fixture f = fixture();
        workActService.signOffline(f.actId, new WorkActSignOfflineRequest("Client"), f.ownerId);
        // The shape of an act signed before V150: SIGNED, a stamp, no snapshot.
        jdbc.update("UPDATE work_act SET signed_document_id = NULL WHERE id = ?", f.actId);

        rewriteEverythingALiveRenderReads(f);

        assertThat(text(workActService.renderPdf(f.actId, f.ownerId))).contains(NEW_COMPANY, NEW_IBAN);
    }

    @Test
    void theStoredSnapshotIsAlreadyCanonical() throws Exception {
        Fixture f = fixture();
        workActService.signOffline(f.actId, new WorkActSignOfflineRequest("Client"), f.ownerId);
        SignedDocument doc = signedDocumentRepository.findById(
                workActRepository.findById(f.actId).orElseThrow().getSignedDocumentId()).orElseThrow();
        // Read back and written again, the text is byte-for-byte the one that was hashed.
        assertThat(SignedSnapshot.canonical(SignedSnapshot.read(doc.getSnapshot(), SignedSnapshot.ActDoc.class)))
                .isEqualTo(doc.getSnapshot());
    }

    // ---- estimates -----------------------------------------------------------------------------

    @Test
    void aSignedEstimate_readsAsItWasSigned_andAReopenKeepsTheSignatureAsHistory() throws Exception {
        Fixture f = fixture();
        Estimate draft = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(f.projectId).orElseThrow())
                .name("Old Sheet").status(EstimateStatus.DRAFT).countInEconomy(true).build());
        estimateItemRepository.save(EstimateItem.builder().estimate(draft).type(ItemType.WORK)
                .name("Works").unit(Unit.M2).quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal("9000.00")).lineTotal(new BigDecimal("9000.00")).sortOrder(0).build());
        EstimateShareLink link = estimateShareLinkRepository.save(EstimateShareLink.builder()
                .estimate(draft).token("tok-" + UUID.randomUUID()).build());

        estimatePortal.sign(link.getToken(), new SignRequest("Client", "+380671112233",
                estimateRepository.findById(draft.getId()).orElseThrow().getVersion()), "203.0.113.7");

        Estimate signed = estimateRepository.findById(draft.getId()).orElseThrow();
        SignedDocument doc = signedDocumentRepository.findById(signed.getSignedDocumentId()).orElseThrow();
        assertThat(doc.getDocHash()).isEqualTo(SignedSnapshot.sha256(doc.getSnapshot()));
        assertThat(doc.getSnapshot()).doesNotContain("203.0.113.7"); // the signer's IP is never printed

        rewriteEverythingALiveRenderReads(f);
        jdbc.update("UPDATE estimates SET name = 'New Sheet' WHERE id = ?", draft.getId());

        // The object-level portal shows the signed sheet's section as it was signed, too.
        String economy = projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(projectRepository.findById(f.projectId).orElseThrow())
                .token("eco-" + UUID.randomUUID()).kind(ShareLinkKind.ECONOMY).revoked(false).build())
                .getToken();
        assertThat(estimatePortal.viewEconomyPortal(economy).estimates())
                .filteredOn(sec -> sec.id().equals(draft.getId()))
                .singleElement().satisfies(sec -> assertThat(sec.name()).isEqualTo("Old Sheet"));

        PublicEstimateView view = estimatePortal.view(link.getToken());
        assertThat(view.contractor().companyName()).isEqualTo(OLD_COMPANY);
        assertThat(view.project().name()).isEqualTo("Old Object");
        assertThat(view.project().clientName()).isEqualTo("Old Client");
        assertThat(text(estimatePortal.renderPdf(link.getToken())))
                .contains(OLD_COMPANY, "Old Object", doc.getDocHash())
                .doesNotContain(NEW_COMPANY, "New Object");

        estimateService.reopen(draft.getId(), f.ownerId);
        assertThat(estimateRepository.findById(draft.getId()).orElseThrow().getSignedDocumentId()).isNull();
        assertThat(signedDocumentRepository.findById(doc.getId())).isPresent();
        // Reopened, it is a live draft again: today's values.
        assertThat(estimatePortal.view(link.getToken()).contractor().companyName()).isEqualTo(NEW_COMPANY);
    }

    // ---- fixture -------------------------------------------------------------------------------

    private record Fixture(UUID ownerId, UUID clientId, UUID projectId, UUID actId) { }

    /** One PRO master with requisites, a client, a SIGNED counted estimate and a DRAFT act on it. */
    private Fixture fixture() {
        String u = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Master").phone("+380000000000").companyName(OLD_COMPANY).legalName(OLD_COMPANY)
                .iban(OLD_IBAN).plan(Plan.PRO).referralCode(u.substring(0, 10)).build());
        Client client = clientRepository.save(Client.builder().owner(owner).fullName("Old Client")
                .phone("+380671112233").build());
        Project project = projectRepository.save(Project.builder().owner(owner).client(client)
                .name("Old Object").address("Street 1").status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder().project(project)
                .status(EstimateStatus.SIGNED).countInEconomy(true).signedAt(Instant.now()).build());
        EstimateItem item = estimateItemRepository.save(EstimateItem.builder().estimate(estimate)
                .type(ItemType.WORK).name("Putty").unit(Unit.M2).quantity(new BigDecimal("100.000"))
                .unitPrice(new BigDecimal("145.00")).lineTotal(new BigDecimal("14500.00")).sortOrder(0).build());
        WorkActResponse act = workActService.create(project.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null, null),
                owner.getId(), null);
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(item.getId(), estimate.getId(), ItemType.WORK, "Putty", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("10.000")))), owner.getId());
        return new Fixture(owner.getId(), client.getId(), project.getId(), act.id());
    }

    private void rewriteEverythingALiveRenderReads(Fixture f) {
        User owner = userRepository.findById(f.ownerId).orElseThrow();
        owner.setCompanyName(NEW_COMPANY);
        owner.setLegalName(NEW_COMPANY);
        owner.setIban(NEW_IBAN);
        userRepository.save(owner);
        Client client = clientRepository.findById(f.clientId).orElseThrow();
        client.setFullName("New Client");
        clientRepository.save(client);
        Project project = projectRepository.findById(f.projectId).orElseThrow();
        project.setName("New Object");
        projectRepository.save(project);
    }

    private String actLink(WorkAct act) {
        return projectShareLinkRepository.save(ProjectShareLink.builder()
                .project(projectRepository.findById(act.getProject().getId()).orElseThrow())
                .token("act-" + UUID.randomUUID())
                .kind(ShareLinkKind.ACT)
                .workAct(act)
                .revoked(false)
                .build()).getToken();
    }

    private String stored(String key) throws IOException {
        try (InputStream in = storage.open(key).orElseThrow()) {
            return new String(in.readNBytes(4));
        }
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
