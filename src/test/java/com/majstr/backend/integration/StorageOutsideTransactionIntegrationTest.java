package com.majstr.backend.integration;

import com.lowagie.text.pdf.PdfReader;
import com.majstr.backend.dto.MessageLinkRequest;
import com.majstr.backend.dto.ProjectPhotoResponse;
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
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
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
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.SignedDocumentRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.MessageLinkService;
import com.majstr.backend.service.ProfileService;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.PublicActPortalService;
import com.majstr.backend.service.PublicEstimateService;
import com.majstr.backend.service.WorkActReceiptService;
import com.majstr.backend.service.WorkActService;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Review B-121: nothing waits on storage while holding a pooled connection.
 *
 * <p>Asserted the way the review proposed: a storage that writes down whether a transaction was active
 * at each call. These paths run on real rows and real Hibernate proxies, so they also prove the second
 * half of the change: a PDF that now renders after the session has closed finds everything it reads
 * already loaded. A lazy association missed there is a LazyInitializationException here.</p>
 *
 * <p>Signing is deliberately absent. It freezes the document under the act's row lock, in one
 * transaction with the signature (see docs/iteration-review-round-5-p1.md).</p>
 */
class StorageOutsideTransactionIntegrationTest extends IntegrationTestBase {

    @MockitoSpyBean StorageService storage;

    @Autowired ProjectPhotoService photos;
    @Autowired PublicEstimateService estimatePortal;
    @Autowired PublicActPortalService actPortal;
    @Autowired WorkActService workActs;
    @Autowired WorkActReceiptService actReceipts;
    @Autowired EstimateService estimates;
    @Autowired ProfileService profiles;
    @Autowired MessageLinkService messageLinks;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository workActRepository;
    @Autowired SignedDocumentRepository signedDocumentRepository;
    @Autowired ProjectShareLinkRepository projectShareLinks;
    @Autowired EstimateShareLinkRepository estimateShareLinks;
    @Autowired ProjectMessageFileRepository messageFiles;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<String> callsInsideATransaction = new CopyOnWriteArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void watchStorage() throws IOException {
        Mockito.doAnswer(call -> record("open", call)).when(storage).open(anyString());
        Mockito.doAnswer(call -> record("contentType", call)).when(storage).contentType(anyString());
        Mockito.doAnswer(call -> record("delete", call)).when(storage).delete(anyString());
        Mockito.doAnswer(call -> record("store", call))
                .when(storage).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    private Object record(String name, org.mockito.invocation.InvocationOnMock call) throws Throwable {
        calls.add(name);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            callsInsideATransaction.add(name);
        }
        return call.callRealMethod();
    }

    private void assertStorageWasUsedOutsideATransaction(String... expected) {
        assertThat(calls).contains(expected);
        assertThat(callsInsideATransaction).isEmpty();
    }

    @Test
    void theWatcherItselfSeesATransactionWhenThereIsOne() {
        // Without this, every assertion below could pass because the watcher never fires.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                storage.contentType("photos/none.jpg");
            } catch (RuntimeException ignored) {
                // Only whether the call was seen matters here.
            }
        });

        assertThat(callsInsideATransaction).containsExactly("contentType");
    }

    // ---- photos -------------------------------------------------------------------------------

    @Test
    void uploadingAndReadingAPhoto() throws Exception {
        Fixture f = fixture();

        ProjectPhotoResponse photo = photos.upload(f.projectId, f.ownerId, jpegFile(), PhotoSource.MANUAL, "Wall", null, null);

        assertThat(photos.readOwnedFile(f.projectId, photo.id(), f.ownerId).readAll()).startsWith((byte) 0xFF);
        assertStorageWasUsedOutsideATransaction("store", "open", "contentType");
    }

    @Test
    void aSharedPhotoThroughEveryPortal() throws Exception {
        Fixture f = fixture();
        ProjectPhotoResponse photo = photos.upload(f.projectId, f.ownerId, jpegFile(), PhotoSource.MANUAL, null, null, null);
        photos.setVisibility(f.projectId, photo.id(), f.ownerId, PhotoVisibility.SHARED);

        assertThat(estimatePortal.readSharedPhoto(estimateLink(f.estimateId), photo.id()).readAll()).isNotEmpty();
        assertThat(estimatePortal.readPortalSharedPhoto(portalLink(f.projectId, ShareLinkKind.PORTAL), photo.id()).readAll())
                .isNotEmpty();
        assertThat(estimatePortal.readEconomySharedPhoto(portalLink(f.projectId, ShareLinkKind.ECONOMY), photo.id()).readAll())
                .isNotEmpty();
        assertStorageWasUsedOutsideATransaction("store", "open");
    }

    // ---- PDFs ---------------------------------------------------------------------------------

    @Test
    void anUnsignedEstimatePdf_forTheClientAndForTheOwner() throws Exception {
        Fixture f = fixture();
        profiles.uploadLogo(f.ownerId, jpegFile());
        Estimate draft = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(f.projectId).orElseThrow())
                .name("Sheet").status(EstimateStatus.DRAFT).countInEconomy(true).build());
        estimateItemRepository.save(EstimateItem.builder().estimate(draft).type(ItemType.WORK)
                .name("Works").unit(Unit.M2).quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal("100.00")).lineTotal(new BigDecimal("100.00")).sortOrder(0).build());

        assertPdf(estimatePortal.renderPdf(estimateLink(draft.getId())));
        assertPdf(estimates.renderPdf(draft.getId(), f.ownerId));
        assertStorageWasUsedOutsideATransaction("store", "open");
    }

    @Test
    void anEstimatePdfWithChosenPhotos() throws Exception {
        Fixture f = fixture();
        ProjectPhotoResponse photo = photos.upload(f.projectId, f.ownerId, jpegFile(), PhotoSource.RECEIPT, null, null, null);

        assertPdf(estimates.renderPdf(f.estimateId, f.ownerId, List.of(photo.id())));
        assertStorageWasUsedOutsideATransaction("store", "open");
    }

    @Test
    void anUnsignedActPdf_withItsReceiptPhoto_forTheClientAndForTheOwner() throws Exception {
        Fixture f = fixture();
        profiles.uploadLogo(f.ownerId, jpegFile());
        actReceipts.add(f.actId, f.ownerId, UUID.randomUUID(), jpegFile(), "Paint", new BigDecimal("50.00"),
                LocalDate.now(), false, null, null);
        WorkAct act = workActRepository.findById(f.actId).orElseThrow();
        act.setStatus(WorkActStatus.SENT);
        workActRepository.save(act);

        assertPdf(actPortal.pdf(actLink(f.actId, f.projectId)));
        assertPdf(workActs.renderPdf(f.actId, f.ownerId));
        assertStorageWasUsedOutsideATransaction("store", "open");
    }

    @Test
    void anActReceiptPhoto_forTheClientAndForTheOwner() throws Exception {
        Fixture f = fixture();
        UUID receiptId = actReceipts.add(f.actId, f.ownerId, UUID.randomUUID(), jpegFile(), "Paint",
                new BigDecimal("50.00"), LocalDate.now(), false, null, null).id();
        WorkAct act = workActRepository.findById(f.actId).orElseThrow();
        act.setStatus(WorkActStatus.SENT);
        workActRepository.save(act);

        assertThat(actPortal.receiptFile(actLink(f.actId, f.projectId), receiptId).readAll()).isNotEmpty();
        assertThat(actReceipts.readOwnedFile(f.actId, receiptId, f.ownerId).readAll()).isNotEmpty();
        assertStorageWasUsedOutsideATransaction("open", "contentType");
    }

    // ---- what a signature stored ---------------------------------------------------------------

    @Test
    void aSignedActIsServedAsTheFileItStored_andFallsBackToTheSnapshotWhenThatHasGone() throws Exception {
        Fixture f = fixture();
        workActs.signOffline(f.actId, new WorkActSignOfflineRequest("Client"), f.ownerId);
        SignedDocument doc = signedDocumentRepository.findById(
                workActRepository.findById(f.actId).orElseThrow().getSignedDocumentId()).orElseThrow();
        byte[] stored;
        try (InputStream in = storage.open(doc.getPdfKey()).orElseThrow()) {
            stored = in.readAllBytes();
        }
        String token = actLink(f.actId, f.projectId);
        calls.clear();
        callsInsideATransaction.clear();

        assertThat(actPortal.pdf(token)).isEqualTo(stored);
        assertThat(workActs.renderPdf(f.actId, f.ownerId)).isEqualTo(stored);
        assertStorageWasUsedOutsideATransaction("open");

        storage.delete(doc.getPdfKey());
        assertPdf(actPortal.pdf(token));
    }

    @Test
    void aSignedEstimateIsServedAsTheFileItStored() throws Exception {
        Fixture f = fixture();
        Estimate draft = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(f.projectId).orElseThrow())
                .name("Sheet").status(EstimateStatus.DRAFT).countInEconomy(true).build());
        estimateItemRepository.save(EstimateItem.builder().estimate(draft).type(ItemType.WORK)
                .name("Works").unit(Unit.M2).quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal("100.00")).lineTotal(new BigDecimal("100.00")).sortOrder(0).build());
        String token = estimateLink(draft.getId());
        estimatePortal.sign(token, new SignRequest("Client", "+380671112233",
                estimateRepository.findById(draft.getId()).orElseThrow().getVersion()), "203.0.113.7");
        SignedDocument doc = signedDocumentRepository.findById(
                estimateRepository.findById(draft.getId()).orElseThrow().getSignedDocumentId()).orElseThrow();
        byte[] stored;
        try (InputStream in = storage.open(doc.getPdfKey()).orElseThrow()) {
            stored = in.readAllBytes();
        }
        calls.clear();
        callsInsideATransaction.clear();

        assertThat(estimatePortal.renderPdf(token)).isEqualTo(stored);
        assertStorageWasUsedOutsideATransaction("open");
    }

    // ---- uploads from elsewhere ----------------------------------------------------------------

    @Test
    void replacingAndRemovingALogo() throws Exception {
        Fixture f = fixture();
        profiles.uploadLogo(f.ownerId, jpegFile());
        String first = userRepository.findById(f.ownerId).orElseThrow().getLogoUrl();
        profiles.uploadLogo(f.ownerId, jpegFile());
        String second = userRepository.findById(f.ownerId).orElseThrow().getLogoUrl();

        assertThat(second).isNotEqualTo(first);
        assertThat(storage.open(first)).isEmpty();

        profiles.deleteLogo(f.ownerId);
        assertThat(userRepository.findById(f.ownerId).orElseThrow().getLogoUrl()).isNull();
        assertThat(storage.open(second)).isEmpty();
        assertStorageWasUsedOutsideATransaction("store", "delete");
    }

    @Test
    void aMessageWithAttachments_throughThePublicForm() throws Exception {
        Fixture f = fixture();
        String token = projectShareLinks.save(ProjectShareLink.builder()
                .project(projectRepository.findById(f.projectId).orElseThrow())
                .token("msg-" + UUID.randomUUID()).kind(ShareLinkKind.MESSAGE).revoked(false).build()).getToken();
        long before = messageFiles.count();

        messageLinks.submit(token, new MessageLinkRequest("Supplier", null, "Invoice"),
                List.of(jpegFile(), jpegFile()), "203.0.113.7");

        assertThat(messageFiles.count()).isEqualTo(before + 2);
        assertStorageWasUsedOutsideATransaction("store");
    }

    // ---- fixture -------------------------------------------------------------------------------

    private record Fixture(UUID ownerId, UUID projectId, UUID estimateId, UUID actId) { }

    private Fixture fixture() {
        String u = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Master").phone("+380000000000").companyName("Company").legalName("Company")
                .plan(Plan.PRO).emailVerified(true).referralCode(u.substring(0, 10)).build());
        Client client = clientRepository.save(Client.builder().owner(owner).fullName("Client")
                .phone("+380671112233").build());
        Project project = projectRepository.save(Project.builder().owner(owner).client(client)
                .name("Object").address("Street 1").status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder().project(project)
                .status(EstimateStatus.SIGNED).countInEconomy(true).signedAt(Instant.now()).build());
        EstimateItem item = estimateItemRepository.save(EstimateItem.builder().estimate(estimate)
                .type(ItemType.WORK).name("Putty").unit(Unit.M2).quantity(new BigDecimal("100.000"))
                .unitPrice(new BigDecimal("145.00")).lineTotal(new BigDecimal("14500.00")).sortOrder(0).build());
        WorkActResponse act = workActs.create(project.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null, null),
                owner.getId(), null);
        workActs.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(item.getId(), estimate.getId(), ItemType.WORK, "Putty", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("10.000")))), owner.getId());
        return new Fixture(owner.getId(), project.getId(), estimate.getId(), act.id());
    }

    private String estimateLink(UUID estimateId) {
        return estimateShareLinks.save(EstimateShareLink.builder()
                .estimate(estimateRepository.findById(estimateId).orElseThrow())
                .token("tok-" + UUID.randomUUID()).build()).getToken();
    }

    private String portalLink(UUID projectId, ShareLinkKind kind) {
        return projectShareLinks.save(ProjectShareLink.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .token("prt-" + UUID.randomUUID()).kind(kind).revoked(false).build()).getToken();
    }

    private String actLink(UUID actId, UUID projectId) {
        return projectShareLinks.save(ProjectShareLink.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .token("act-" + UUID.randomUUID()).kind(ShareLinkKind.ACT)
                .workAct(workActRepository.findById(actId).orElseThrow())
                .revoked(false).build()).getToken();
    }

    private static MockMultipartFile jpegFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", out.toByteArray());
    }

    private static void assertPdf(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isPositive();
        } finally {
            reader.close();
        }
    }
}
