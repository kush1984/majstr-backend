package com.majstr.backend.integration;

import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentSurplusTransferRequest;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.PaymentOverflowResolution;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two ways a payment TRANSFER rewrote money that had already been recorded (review B-69). Both
 * need the real database: one is a primary key colliding on a replay, the other is which DAY a row
 * ends up carrying.
 */
class PaymentTransferIntegrationTest extends IntegrationTestBase {

    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    private static final LocalDate AUG_28 = LocalDate.of(2026, 8, 28);

    @Autowired PaymentService paymentService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PaymentReceiptRepository receiptRepository;

    @Test
    void transferOverflow_replayedUnderTheSameEntityId_recordsTheSurplusOnce() {
        User owner = newOwner();
        Project p = newProject(owner);
        ProjectPaymentResponse first = stage(p, owner, "Аванс", "5000.00");
        stage(p, owner, "Після робіт", "5000.00");

        UUID entityId = UUID.randomUUID();
        PaymentReceiptRequest req = new PaymentReceiptRequest(first.id(), null,
                new BigDecimal("8000.00"), AUG_28, PaymentOverflowResolution.TRANSFER, false);

        List<PaymentReceiptResponse> firstCall =
                paymentService.addReceipt(p.getId(), owner.getId(), req, entityId);
        assertThat(firstCall).hasSize(2);
        // The replay an offline outbox sends when the first answer never arrived.
        List<PaymentReceiptResponse> replay =
                paymentService.addReceipt(p.getId(), owner.getId(), req, entityId);

        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).id()).isEqualTo(firstCall.get(0).id());
        assertThat(replay.get(1).id()).isEqualTo(firstCall.get(1).id());
        assertThat(receiptRepository.sumByProjectId(p.getId())).isEqualByComparingTo("8000.00");
    }

    @Test
    void transferOverflow_onAFullyReceivedStage_isAlsoIdempotent() {
        // The case with NO closing row at all: the whole amount overflows, so nothing carried the
        // caller's id and the replay check had nothing to recognise — the overflow was written
        // twice, inventing money the client never paid.
        User owner = newOwner();
        Project p = newProject(owner);
        ProjectPaymentResponse first = stage(p, owner, "Аванс", "5000.00");
        stage(p, owner, "Після робіт", "5000.00");
        paymentService.addReceipt(p.getId(), owner.getId(), new PaymentReceiptRequest(
                first.id(), null, new BigDecimal("5000.00"), AUG_20, null, false), null);

        UUID entityId = UUID.randomUUID();
        PaymentReceiptRequest req = new PaymentReceiptRequest(first.id(), null,
                new BigDecimal("2000.00"), AUG_28, PaymentOverflowResolution.TRANSFER, false);
        List<PaymentReceiptResponse> firstCall =
                paymentService.addReceipt(p.getId(), owner.getId(), req, entityId);
        List<PaymentReceiptResponse> replay =
                paymentService.addReceipt(p.getId(), owner.getId(), req, entityId);

        assertThat(firstCall).hasSize(1);
        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).id()).isEqualTo(firstCall.get(0).id());
        assertThat(receiptRepository.sumByProjectId(p.getId())).isEqualByComparingTo("7000.00");
    }

    @Test
    void movingASurplus_keepsEachSourceRowsDayAndItsRefundFlag() {
        // 3 000 received on 28 Aug above a 5 000 stage, moved later: the old code wrote ONE row
        // dated today() with no flag, so August lost 3 000 and the month of the move gained them
        // («Мої гроші» reads received_at as the authoritative day), and a «повернення за матеріал»
        // came back as ordinary earnings.
        User owner = newOwner();
        Project p = newProject(owner);
        ProjectPaymentResponse first = stage(p, owner, "Аванс", "5000.00");
        ProjectPaymentResponse second = stage(p, owner, "Після робіт", "5000.00");
        paymentService.addReceipt(p.getId(), owner.getId(), new PaymentReceiptRequest(
                first.id(), null, new BigDecimal("5000.00"), AUG_20, null, false), null);
        paymentService.addReceipt(p.getId(), owner.getId(), new PaymentReceiptRequest(
                first.id(), null, new BigDecimal("3000.00"), AUG_28,
                PaymentOverflowResolution.RESERVE, true), null);

        paymentService.transferSurplus(p.getId(), owner.getId(),
                new PaymentSurplusTransferRequest(first.id(), second.id()));

        List<PaymentReceipt> moved =
                receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(second.id());
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).getAmount()).isEqualByComparingTo("3000.00");
        assertThat(moved.get(0).getReceivedAt()).isEqualTo(AUG_28);
        assertThat(moved.get(0).isMaterialRefund()).isTrue();
        // Nothing appeared or vanished on the way.
        assertThat(receiptRepository.sumByProjectId(p.getId())).isEqualByComparingTo("8000.00");
    }

    @Test
    void movingASurplusOntoTheSameStage_isRefused() {
        User owner = newOwner();
        Project p = newProject(owner);
        ProjectPaymentResponse only = stage(p, owner, "Аванс", "5000.00");
        paymentService.addReceipt(p.getId(), owner.getId(), new PaymentReceiptRequest(
                only.id(), null, new BigDecimal("8000.00"), AUG_28,
                PaymentOverflowResolution.RESERVE, false), null);

        assertThatThrownBy(() -> paymentService.transferSurplus(p.getId(), owner.getId(),
                new PaymentSurplusTransferRequest(only.id(), only.id())))
                .isInstanceOf(PaymentValidationException.class);
        assertThat(receiptRepository.sumByProjectId(p.getId())).isEqualByComparingTo("8000.00");
    }

    // ---- fixtures ---------------------------------------------------------------

    private ProjectPaymentResponse stage(Project p, User owner, String purpose, String amount) {
        return paymentService.add(p.getId(), owner.getId(),
                new ProjectPaymentRequest(new BigDecimal(amount), null, null, purpose), null);
    }

    private User newOwner() {
        String u = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO) // OBJECT_ECONOMY is PRO-gated; every payment write requires it
                .referralCode(u.substring(0, 10)).build());
    }

    private Project newProject(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
    }
}
