package com.majstr.backend.integration;

import com.majstr.backend.entity.CashDirection;
import com.majstr.backend.entity.CashEntry;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActLineKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.AdminUserDeleteRefusedException;
import com.majstr.backend.repository.CashEntryRepository;
import com.majstr.backend.repository.ClientRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.AdminUserDeletionService;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin's whole-account delete against a real schema: the cascade has to clear every table,
 * including {@code work_act.user_id} (the one FK to {@code users} with no ON DELETE action), and an
 * object holding signed papers — which the master's own delete refuses — goes with it.
 */
class AdminUserDeletionIntegrationTest extends IntegrationTestBase {

    @Autowired AdminUserDeletionService deletionService;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired WorkActRepository actRepository;
    @Autowired WorkActItemRepository actItemRepository;
    @Autowired ProjectPhotoRepository photoRepository;
    @Autowired CashEntryRepository cashEntryRepository;
    @Autowired StorageService storage;

    @Test
    void aMasterWithSignedPapersAndFiles_isGoneWithEverythingHeOwned() throws IOException {
        User admin = newUser(Role.ADMIN, null);
        User master = newUser(Role.USER, null);
        User invited = newUser(Role.USER, master.getId());

        String logoKey = storeFile("logos");
        master.setLogoUrl(logoKey);
        userRepository.save(master);

        Client client = clientRepository.save(Client.builder()
                .owner(master).fullName("Клієнт").phone("+380000000001").build());
        Project project = projectRepository.save(Project.builder()
                .owner(master).client(client).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(true).build());
        EstimateItem line = itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Роботи").unit(Unit.M2)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal("1000.00"))
                .lineTotal(new BigDecimal("1000.00")).sortOrder(0).build());
        WorkAct act = actRepository.save(WorkAct.builder()
                .userId(master.getId()).project(project).number("1").kind(WorkActKind.INTERIM)
                .status(WorkActStatus.SIGNED).issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(1)).periodTo(LocalDate.now())
                .signedAt(Instant.now()).build());
        actItemRepository.save(WorkActItem.builder()
                .workAct(act).lineKind(WorkActLineKind.ESTIMATE)
                .estimateItemId(line.getId()).estimateId(estimate.getId())
                .type(ItemType.WORK).name("Роботи").unit(Unit.M2)
                .unitPrice(new BigDecimal("1000.00")).quantity(BigDecimal.ONE.setScale(3))
                .lineTotal(new BigDecimal("1000.00"))
                .cumulativeBefore(BigDecimal.ZERO.setScale(3)).sortOrder(0).build());

        String photoKey = storeFile("photos");
        photoRepository.save(ProjectPhoto.builder()
                .id(UUID.randomUUID()).projectId(project.getId()).storageKey(photoKey)
                .source(PhotoSource.RECEIPT).visibility(PhotoVisibility.PRIVATE).build());
        String cashKey = storeFile("cash");
        cashEntryRepository.save(CashEntry.builder()
                .id(UUID.randomUUID()).ownerId(master.getId()).direction(CashDirection.EXPENSE)
                .amount(new BigDecimal("300.00")).happenedOn(LocalDate.now()).happenedAt(Instant.now())
                .storageKey(cashKey).build());

        deletionService.delete(master.getId(), admin.getId());

        assertThat(userRepository.findById(master.getId())).isEmpty();
        assertThat(clientRepository.findById(client.getId())).isEmpty();
        assertThat(projectRepository.findById(project.getId())).isEmpty();
        assertThat(estimateRepository.findById(estimate.getId())).isEmpty();
        assertThat(actRepository.findById(act.getId())).isEmpty();
        assertThat(cashEntryRepository.findStorageKeysByOwnerId(master.getId())).isEmpty();
        // Files go after the commit, not with the rows only.
        assertThat(storage.open(logoKey)).isEmpty();
        assertThat(storage.open(photoKey)).isEmpty();
        assertThat(storage.open(cashKey)).isEmpty();
        // Someone he invited keeps the account; only the pointer back to him is cleared.
        assertThat(userRepository.findById(invited.getId()))
                .hasValueSatisfying(u -> assertThat(u.getReferredByUserId()).isNull());
    }

    @Test
    void anAdminCannotDeleteHimselfOrAnotherAdmin() {
        User admin = newUser(Role.ADMIN, null);
        User otherAdmin = newUser(Role.ADMIN, null);

        assertThatThrownBy(() -> deletionService.delete(admin.getId(), admin.getId()))
                .isInstanceOf(AdminUserDeleteRefusedException.class);
        assertThatThrownBy(() -> deletionService.delete(otherAdmin.getId(), admin.getId()))
                .isInstanceOf(AdminUserDeleteRefusedException.class);
        assertThat(userRepository.findById(otherAdmin.getId())).isPresent();
    }

    private String storeFile(String prefix) throws IOException {
        byte[] bytes = {1, 2, 3};
        return storage.store(new ByteArrayInputStream(bytes), bytes.length, prefix, "jpg", "image/jpeg").key();
    }

    private User newUser(Role role, UUID referredBy) {
        String u = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(u + "@majstr.test").emailCanonical(u + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.FREE).role(role).referredByUserId(referredBy)
                .referralCode(u.substring(0, 10)).build());
    }
}
