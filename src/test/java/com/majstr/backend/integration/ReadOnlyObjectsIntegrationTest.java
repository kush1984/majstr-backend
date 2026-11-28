package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ObjectReadOnlyException;
import com.majstr.backend.exception.ObjectUnlockException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.PaymentService;
import com.majstr.backend.service.ProjectNoteService;
import com.majstr.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lapsed PRO keeps every object he made, but on FREE only the first ones stay his to change
 * (iteration-read-only-objects, {@code ObjectWriteGuard}) — against Postgres, through the services the
 * controllers call.
 */
@SpringBootTest
class ReadOnlyObjectsIntegrationTest extends IntegrationTestBase {

    @Autowired ProjectService projectService;
    @Autowired EstimateService estimateService;
    @Autowired PaymentService paymentService;
    @Autowired ProjectNoteService noteService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User master;
    private UUID first;
    private UUID second;
    private UUID third;
    private UUID fourth;
    private UUID transferred;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        master = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Хитрий").emailVerified(true)
                .plan(Plan.PRO).role(Role.USER).referralCode(unique.substring(0, 10))
                .build());
        // Made while on PRO, a day apart — the order the rule reads is the order they were made in.
        first = made("Перший", 4);
        second = made("Другий", 3);
        third = made("Третій", 2);
        fourth = made("Четвертий", 1);
        transferred = projectService.createTransferred("Перенесений", null, null, master.getId()).getId();
        jdbc.update("UPDATE projects SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), transferred);
    }

    @Test
    void onFreeOnlyTheFirstObjectsChange_theRestAreViewOnly() {
        lapseToFree();

        Map<UUID, Boolean> readOnly = projectService.listForOwner(master.getId(), null).stream()
                .collect(Collectors.toMap(ProjectResponse::id, ProjectResponse::readOnly));
        assertThat(readOnly).containsEntry(first, false).containsEntry(second, false)
                .containsEntry(third, true).containsEntry(fourth, true)
                // Outside the cap by design, and older than all of them — still not his to change.
                .containsEntry(transferred, true);
        assertThat(projectService.get(third, master.getId()).readOnly()).isTrue();

        assertThatThrownBy(() -> newEstimate(third)).isInstanceOf(ObjectReadOnlyException.class);
        assertThatThrownBy(() -> projectService.update(third, new ProjectRequest("Нова назва", null, null, null),
                master.getId())).isInstanceOf(ObjectReadOnlyException.class);
        assertThatThrownBy(() -> noteService.add(third, master.getId(), new NoteRequest(null, null, "Нотатка")))
                .isInstanceOf(ObjectReadOnlyException.class);
        assertThatCode(() -> newEstimate(first)).doesNotThrowAnyException();
        assertThatCode(() -> noteService.add(first, master.getId(), new NoteRequest(null, null, "Нотатка")))
                .doesNotThrowAnyException();
    }

    @Test
    void aViewOnlyObjectStillRecordsMoneyAndChangesStatus() {
        lapseToFree();

        assertThatCode(() -> paymentService.addReceipt(third, master.getId(),
                new PaymentReceiptRequest("Аванс", new BigDecimal("1000.00"), LocalDate.now(), false), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> projectService.updateStatus(third, ProjectStatus.COMPLETED, master.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void anEstimateMadeOnProEditsOnProButNotOnceTheObjectIsViewOnly() {
        EstimateResponse estimate = newEstimate(fourth);
        lapseToFree();

        assertThatThrownBy(() -> estimateService.delete(estimate.id(), master.getId()))
                .isInstanceOf(ObjectReadOnlyException.class);
        assertThatCode(() -> estimateService.get(estimate.id(), master.getId())).doesNotThrowAnyException();

        backToPro();
        assertThatCode(() -> estimateService.delete(estimate.id(), master.getId())).doesNotThrowAnyException();
    }

    @Test
    void closingOneOfTheFirstObjectsDoesNotPromoteTheNext() {
        lapseToFree();
        projectService.updateStatus(first, ProjectStatus.CANCELLED, master.getId());

        assertThat(projectService.get(third, master.getId()).readOnly()).isTrue();
    }

    @Test
    void theFreeEstimateCapCountsEstimatesEverMade() {
        lapseToFree();
        EstimateResponse a = newEstimate(first);
        newEstimate(first);
        newEstimate(first);
        estimateService.delete(a.id(), master.getId());

        assertThatThrownBy(() -> newEstimate(first)).isInstanceOf(LimitExceededException.class);
    }

    @Test
    void aBoughtObjectCreditUnlocksOneViewOnlyObjectForGood() {
        lapseToFree();
        assertThatThrownBy(() -> projectService.unlock(third, master.getId()))
                .isInstanceOfSatisfying(ObjectUnlockException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_OBJECT_CREDIT"));

        jdbc.update("UPDATE users SET object_credits = 1 WHERE id = ?", master.getId());
        // A writable object is refused, the credit kept — 149 ₴ is never spent on nothing.
        assertThatThrownBy(() -> projectService.unlock(first, master.getId()))
                .isInstanceOfSatisfying(ObjectUnlockException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("OBJECT_NOT_LOCKED"));

        ProjectResponse unlocked = projectService.unlock(third, master.getId());

        assertThat(unlocked.readOnly()).isFalse();
        assertThat(unlocked.paid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT object_credits FROM users WHERE id = ?", Integer.class,
                master.getId())).isZero();
        assertThatCode(() -> newEstimate(third)).doesNotThrowAnyException();
        // Taking a slot of its own, it promotes nothing: the fourth stays view-only.
        assertThat(projectService.get(fourth, master.getId()).readOnly()).isTrue();
        // A transferred object can be bought back the same way.
        jdbc.update("UPDATE users SET object_credits = 1 WHERE id = ?", master.getId());
        assertThat(projectService.unlock(transferred, master.getId()).readOnly()).isFalse();
    }

    private UUID made(String name, int daysAgo) {
        UUID id = projectService.create(new ProjectRequest(name, null, null, null), master.getId()).id();
        jdbc.update("UPDATE projects SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)), id);
        return id;
    }

    private EstimateResponse newEstimate(UUID projectId) {
        return estimateService.createForProject(projectId, new EstimateCreateRequest(null, null, "Кошторис"),
                master.getId());
    }

    private void lapseToFree() {
        jdbc.update("UPDATE users SET plan = 'FREE' WHERE id = ?", master.getId());
    }

    private void backToPro() {
        jdbc.update("UPDATE users SET plan = 'PRO' WHERE id = ?", master.getId());
    }
}
