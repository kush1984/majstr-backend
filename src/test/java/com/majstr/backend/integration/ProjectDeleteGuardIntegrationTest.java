package com.majstr.backend.integration;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ProjectHasSignedMoneyException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An object that carries a signature or money is archived, never deleted (review B-70).
 *
 * <p>Every neighbouring door already refused this one row at a time — a SIGNED estimate cannot be
 * deleted, a signed act cannot be deleted, an estimate with acts cannot be uncounted — and the
 * object delete cascaded past all of them at once. «Мої гроші» is a LENS over exactly those rows, so
 * tidying away a finished job silently rewrote the master's closed months.</p>
 */
class ProjectDeleteGuardIntegrationTest extends IntegrationTestBase {

    @Autowired ProjectService projectService;
    @Autowired com.majstr.backend.repository.ProjectReceiptRepository projectReceiptRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired ObjectExpenseRepository expenseRepository;

    @Test
    void anObjectWithASignedEstimate_isNotDeletable() {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimate(p);

        assertThatThrownBy(() -> projectService.delete(p.getId(), owner.getId()))
                .isInstanceOf(ProjectHasSignedMoneyException.class);
        assertThat(projectRepository.findById(p.getId())).isPresent();
    }

    @Test
    void anObjectWithARecordedExpense_isNotDeletableEither() {
        // No signature anywhere — just a cost the master typed, which «Мої гроші» already counted
        // into a month he has read.
        User owner = newOwner();
        Project p = newProject(owner);
        expenseRepository.save(ObjectExpense.builder()
                .objectId(p.getId())
                .category(ExpenseCategory.MATERIALS)
                .source(ExpenseSource.MANUAL)
                .note("Клей")
                .amount(new BigDecimal("450.00"))
                .spentAt(LocalDate.now())
                .build());

        assertThatThrownBy(() -> projectService.delete(p.getId(), owner.getId()))
                .isInstanceOf(ProjectHasSignedMoneyException.class);
        assertThat(projectRepository.findById(p.getId())).isPresent();
    }

    @Test
    void anObjectWithATillReceipt_isNotDeletable() {
        // B-101. A reimbursable till receipt posts no expense, but «Мої гроші» counts it as an
        // outlay — so deleting the object moved last month's «Заробив» by the receipt.
        User owner = newOwner();
        Project p = newProject(owner);
        projectReceiptRepository.save(com.majstr.backend.entity.ProjectReceipt.builder()
                .projectId(p.getId()).label("Епіцентр").amount(new BigDecimal("2000.00"))
                .reimbursable(true).sortOrder(0).build());

        assertThatThrownBy(() -> projectService.delete(p.getId(), owner.getId()))
                .isInstanceOf(ProjectHasSignedMoneyException.class);
    }

    @Test
    void anObjectWithNothingButDrafts_stillDeletesCleanly() {
        // The guard must not turn every mis-created object into a permanent resident.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate draft = estimateRepository.save(Estimate.builder()
                .project(p).status(EstimateStatus.DRAFT).countInEconomy(true).build());
        addWork(draft, "1000.00");

        projectService.delete(p.getId(), owner.getId());

        assertThat(projectRepository.findById(p.getId())).isEmpty();
    }

    // ---- fixtures ---------------------------------------------------------------

    private Estimate signedEstimate(Project p) {
        Estimate est = estimateRepository.save(Estimate.builder()
                .project(p).status(EstimateStatus.SIGNED).countInEconomy(true).build());
        addWork(est, "10000.00");
        return est;
    }

    private void addWork(Estimate estimate, String amount) {
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Роботи").unit(Unit.M2)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal(amount))
                .lineTotal(new BigDecimal(amount).setScale(2)).sortOrder(0).build());
    }

    private User newOwner() {
        String u = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO).referralCode(u.substring(0, 10)).build());
    }

    private Project newProject(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.COMPLETED).build());
    }
}
