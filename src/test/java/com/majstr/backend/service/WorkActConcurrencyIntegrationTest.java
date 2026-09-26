package com.majstr.backend.service;

import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.integration.IntegrationTestBase;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signature is a FREEZE, so every write to an act must fall on one side of it — never in the
 * middle (review B-60).
 *
 * <p>The window was ordinary, not contrived: a receipt is created in two transactions with a photo
 * upload between them, and «Підписати» is exactly what happens next. A row landing in that gap is
 * counted by «Прийнято актами» while sitting outside the {@code doc_hash} the client signed and
 * outside the ADDENDUM that would put it into «За договором» — so the two axes drift and the PDF in
 * the client's hand disagrees with «До сплати».</p>
 *
 * <p>Lives in {@code ..service} rather than {@code ..integration} on purpose: the first test drives
 * the two halves of the receipt create ({@link WorkActReceiptCreator}, package-private) directly,
 * which is the only way to put a signature INSIDE the gap deterministically instead of hoping a
 * thread loses a race.</p>
 */
class WorkActConcurrencyIntegrationTest extends IntegrationTestBase {

    /**
     * How long the signing thread is given to reach the act row and block on it. Generous, and only
     * ever a lower bound on «it has not finished» — no assertion races it.
     */
    private static final long SETTLE_MS = 1500;

    @Autowired WorkActService workActService;
    @Autowired WorkActReceiptService receiptService;
    @Autowired WorkActReceiptCreator receiptCreator;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository workActRepository;
    @Autowired WorkActReceiptRepository receiptRepository;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void receiptAddAfterConcurrentSign_refused() throws Exception {
        Fixture f = fixture("10.000");

        // The master taps «додати чек»: the pre-flight check passes, the act is open…
        UUID receiptId = UUID.randomUUID();
        WorkActReceiptCreator.Prepared prepared = receiptCreator.prepare(f.actId, f.ownerId, receiptId);
        assertThat(prepared.replay()).isNull();

        // …and while the photo goes up over a site connection, the act is signed.
        workActService.signOffline(f.actId, new WorkActSignOfflineRequest("Клієнт"), f.ownerId);

        // The insert re-asks under the act's own lock, so the receipt is refused instead of landing
        // at 1 800 ₴ on a frozen document — unpriceable, undeletable, yet counted.
        assertThatThrownBy(() -> receiptCreator.attempt(f.actId, receiptId, "Епіцентр",
                new BigDecimal("1800.00"), LocalDate.now(), "act-receipts/orphan.jpg", 0))
                .isInstanceOf(WorkActSignedException.class);
        assertThat(receiptRepository.findByWorkActIdNewestFirst(f.actId)).isEmpty();
    }

    @Test
    void replaceItemsDuringSign_signLosesOrWaits() throws Exception {
        Fixture f = fixture("10.000");
        CountDownLatch editing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A rewrites the lines and sits on the act row without committing.
            Future<?> editor = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                workActService.replaceItems(f.actId, new WorkActItemsRequest(List.of(
                        estimateLine(f, "10.000"),
                        new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж", null,
                                Unit.M2, new BigDecimal("725.00"), new BigDecimal("2.000")))),
                        f.ownerId);
                editing.countDown();
                await(release);
                return null;
            }));
            assertThat(editing.await(30, TimeUnit.SECONDS)).isTrue();

            // Thread B signs. It must WAIT for the lines, not freeze the document it read first.
            Future<WorkActResponse> signer = pool.submit(() -> workActService.signOffline(
                    f.actId, new WorkActSignOfflineRequest("Клієнт"), f.ownerId));
            Thread.sleep(SETTLE_MS);
            assertThat(signer.isDone())
                    .as("the signature must block on the act row, not sign a stale document")
                    .isFalse();

            release.countDown();
            editor.get(30, TimeUnit.SECONDS);
            WorkActResponse signed = signer.get(30, TimeUnit.SECONDS);

            // 10 × 145 + 2 × 725: the act was signed as it stood AFTER the edit, and the doc_hash
            // was computed over exactly these two lines.
            assertThat(signed.items()).hasSize(2);
            assertThat(signed.total()).isEqualByComparingTo("2900.00");
            assertThat(workActRepository.findById(f.actId).orElseThrow().getDocHash()).isNotBlank();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void childWritesBumpTheActVersion() throws Exception {
        // B-61 hangs on this: @Version only moves when the act ROW is written, and a line or a
        // receipt is a CHILD row. Without the bump the version the client's portal page is holding
        // still looks current after «До сплати» changed under him.
        Fixture f = fixture("10.000");
        long afterLines = version(f.actId);

        receiptService.add(f.actId, f.ownerId, null, receiptPhoto(), "Епіцентр",
                new BigDecimal("1800.00"), LocalDate.now(), false);
        long afterReceipt = version(f.actId);
        assertThat(afterReceipt).isGreaterThan(afterLines);

        workActService.replaceItems(f.actId,
                new WorkActItemsRequest(List.of(estimateLine(f, "12.000"))), f.ownerId);
        assertThat(version(f.actId)).isGreaterThan(afterReceipt);
    }

    // ---- fixtures ---------------------------------------------------------------

    private record Fixture(UUID ownerId, UUID projectId, UUID estimateId, UUID estimateItemId,
                           UUID actId) { }

    /** One master, one object, one SIGNED counted estimate of 100 м² × 145 ₴, one DRAFT act. */
    private Fixture fixture(String quantity) {
        String u = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO).referralCode(u.substring(0, 10)).build());
        Project project = projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true).build());
        EstimateItem item = estimateItemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Шпаклювання").unit(Unit.M2)
                .quantity(new BigDecimal("100.000")).unitPrice(new BigDecimal("145.00"))
                .lineTotal(new BigDecimal("14500.00")).sortOrder(0).build());
        WorkActResponse act = workActService.create(project.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(),
                        null, null, null, null, null, null), owner.getId(), null);

        Fixture f = new Fixture(owner.getId(), project.getId(), estimate.getId(), item.getId(), act.id());
        workActService.replaceItems(act.id(),
                new WorkActItemsRequest(List.of(estimateLine(f, quantity))), owner.getId());
        return f;
    }

    private static WorkActItemsRequest.Line estimateLine(Fixture f, String quantity) {
        return new WorkActItemsRequest.Line(f.estimateItemId, f.estimateId, ItemType.WORK,
                "Шпаклювання", null, Unit.M2, new BigDecimal("145.00"), new BigDecimal(quantity));
    }

    private long version(UUID actId) {
        WorkAct act = workActRepository.findById(actId).orElseThrow();
        return act.getVersion();
    }

    /** A minimal valid JPEG upload — the photo is mandatory on an act receipt. */
    private static MockMultipartFile receiptPhoto() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", jpeg);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
