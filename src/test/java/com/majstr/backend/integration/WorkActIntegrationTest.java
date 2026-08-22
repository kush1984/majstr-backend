package com.majstr.backend.integration;

import com.majstr.backend.dto.ActProgressResponse;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActItemsRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.dto.WorkActSignOfflineRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.exception.WorkActConflictException;
import com.majstr.backend.exception.WorkActOpenException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActRepository;
import com.majstr.backend.service.WorkActService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Work acts end-to-end through the real {@link WorkActService} against real Postgres — the class of
 * test the acts core needs (numbering, the SIGNED-quantity aggregate, the UNIQUE constraint, the
 * ADDENDUM transaction all live in SQL / cross-entity logic no Mockito test can reach).
 */
class WorkActIntegrationTest extends IntegrationTestBase {

    @Autowired WorkActService workActService;
    @Autowired com.majstr.backend.service.ObjectExpenseService objectExpenseService;
    @Autowired com.majstr.backend.service.EstimateService estimateService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired WorkActRepository workActRepository;
    @Autowired com.majstr.backend.service.WorkActReceiptService receiptService;
    @Autowired com.majstr.backend.repository.ProjectPhotoRepository photoRepository;

    @Test
    void numberingIsContinuousPerMaster_acrossObjects() {
        User owner = newOwner();
        Project a = newProject(owner);
        Project b = newProject(owner);
        signedEstimateWithLine(a, "Робота A", "100.000", "145.00");
        signedEstimateWithLine(b, "Робота B", "100.000", "145.00");

        // A draft act on object A does NOT block a first act on object B (one-open-act is per
        // object), and the running numbers are continuous for the master: 1, then 2.
        WorkActResponse act1 = createInterim(a.getId(), owner.getId());
        WorkActResponse act2 = createInterim(b.getId(), owner.getId());

        assertThat(act1.number()).isEqualTo("1");
        assertThat(act2.number()).isEqualTo("2");
    }

    @Test
    void oneOpenActPerObject() {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        createInterim(p.getId(), owner.getId());

        assertThatThrownBy(() -> createInterim(p.getId(), owner.getId()))
                .isInstanceOf(WorkActOpenException.class);
    }

    @Test
    void uniqueUserNumberConstraintIsEnforced() {
        User owner = newOwner();
        Project p = newProject(owner);
        workActRepository.saveAndFlush(rawAct(owner, p, "7", WorkActKind.INTERIM));

        assertThatThrownBy(() -> workActRepository.saveAndFlush(rawAct(owner, p, "7", WorkActKind.INTERIM)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cumulativeProgressAccumulatesAcrossSignedActs_frozenPerAct() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Шпаклювання", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        // Act 1: close 40 of 100, sign it.
        WorkActResponse act1 = createInterim(p.getId(), owner.getId());
        setSingleLine(act1.id(), owner.getId(), est.getId(), lineId, "40.000", "145.00");
        workActService.signOffline(act1.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        // Progress now shows 40 done, 60 remaining.
        ActProgressResponse progress = workActService.progress(p.getId(), owner.getId());
        ActProgressResponse.Line pl = progress.lines().stream()
                .filter(l -> l.estimateItemId().equals(lineId)).findFirst().orElseThrow();
        assertThat(pl.done()).isEqualByComparingTo("40.000");
        assertThat(pl.remaining()).isEqualByComparingTo("60.000");

        // Act 2: its line freezes cumulative_before = 40, closes 30 more.
        WorkActResponse act2 = createInterim(p.getId(), owner.getId());
        WorkActResponse act2WithItems = setSingleLine(act2.id(), owner.getId(), est.getId(), lineId, "30.000", "145.00");
        assertThat(act2WithItems.items().get(0).cumulativeBefore()).isEqualByComparingTo("40.000");
        workActService.signOffline(act2.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ActProgressResponse after = workActService.progress(p.getId(), owner.getId());
        ActProgressResponse.Line pl2 = after.lines().stream()
                .filter(l -> l.estimateItemId().equals(lineId)).findFirst().orElseThrow();
        assertThat(pl2.done()).isEqualByComparingTo("70.000");
        assertThat(pl2.remaining()).isEqualByComparingTo("30.000");
    }

    @Test
    void actTitleRoundTrips_andClearsToNull() {
        // The stage name («Штукатурні роботи») — optional, editable until signed, blank → null.
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");

        WorkActResponse created = workActService.create(p.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, "Штукатурні роботи", LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null, null),
                owner.getId(), null);
        assertThat(created.title()).isEqualTo("Штукатурні роботи");

        WorkActResponse updated = workActService.updateHeader(created.id(),
                new com.majstr.backend.dto.WorkActUpdateRequest(WorkActKind.INTERIM, "  ", LocalDate.now(),
                        LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null, null, null, null),
                owner.getId());
        assertThat(updated.title()).isNull();
    }

    @Test
    void sentAct_canBeRecalledToDraft_orMarkedRejected_andRejectedCanComeBack() throws Exception {
        // REJECTED used to be unreachable — a SENT act the client declined wedged the object
        // forever (one-open-act + SENT is neither editable in status nor deletable). Review fix:
        // the owner records the outcome himself.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00");
        markSent(act.id());

        // SENT → REJECTED: the declined act stays as history and stops blocking new acts…
        WorkActResponse rejected = workActService.changeStatus(act.id(), WorkActStatus.REJECTED, owner.getId());
        assertThat(rejected.status()).isEqualTo(WorkActStatus.REJECTED);
        assertThat(createInterim(p.getId(), owner.getId()).status()).isEqualTo(WorkActStatus.DRAFT);
    }

    @Test
    void rejectedToDraft_reentersTheOneOpenActRule() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse first = createInterim(p.getId(), owner.getId());
        setSingleLine(first.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00");
        markSent(first.id());
        workActService.changeStatus(first.id(), WorkActStatus.REJECTED, owner.getId());
        createInterim(p.getId(), owner.getId()); // a NEW open act appeared meanwhile

        // …so the rejected one cannot come back to DRAFT while another act is open.
        assertThatThrownBy(() -> workActService.changeStatus(first.id(), WorkActStatus.DRAFT, owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void recallToDraft_clearsSentAt_andSignedActsCannotBeMoved() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00");
        markSent(act.id());

        WorkActResponse recalled = workActService.changeStatus(act.id(), WorkActStatus.DRAFT, owner.getId());
        assertThat(recalled.status()).isEqualTo(WorkActStatus.DRAFT);
        assertThat(recalled.sentAt()).isNull();

        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());
        assertThatThrownBy(() -> workActService.changeStatus(act.id(), WorkActStatus.DRAFT, owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void offlineSign_leavesTheSameDocHashStampAsThePortal() throws Exception {
        // Review fix: the offline path used to leave NO tamper stamp — now both sign paths share
        // ActSignedCopyService.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00");

        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThat(workActRepository.findById(act.id()).orElseThrow().getDocHash())
                .isNotNull().hasSize(64);
    }

    @Test
    void withYearNumber_followsTheIssueYearWhileOpen() {
        // «7/2026» is display + identity split: the year part follows issuedAt while the act is
        // open (review fix); the sequence — unique across years — never changes.
        User owner = newOwner();
        owner.setActNumberFormat(com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);
        userRepository.save(owner);
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        int year = LocalDate.now().getYear();
        assertThat(act.number()).isEqualTo("1/" + year);

        WorkActResponse updated = workActService.updateHeader(act.id(),
                new com.majstr.backend.dto.WorkActUpdateRequest(WorkActKind.INTERIM, null,
                        LocalDate.now().plusYears(1), LocalDate.now().minusDays(7), LocalDate.now(),
                        null, null, null, null, null, null, null, null),
                owner.getId());

        assertThat(updated.number()).isEqualTo("1/" + (year + 1));
    }

    @Test
    void itemizedReceipt_billsThroughItsPositions_neverTwice() throws Exception {
        // Round 2: the recognized positions live as act lines; the receipt row stays as proof but
        // its amount must not be billed again — not in receiptsTotal, not in the ADDENDUM, not in
        // «Прийнято актами». The expense posting is the one place it still counts.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00"); // 14 500
        UUID line = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        // The act: 50 м² of estimate works (7 250) + the receipt's positions as additional lines
        // (2 × 241.75 = 483.50) + the itemized receipt itself (483.50, reference-only).
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(line, est.getId(), ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("50.000")),
                new WorkActItemsRequest.Line(null, null, ItemType.MATERIAL, "Клей Ceresit CM-11", null,
                        Unit.PIECE, new BigDecimal("241.75"), new BigDecimal("2.000")))), owner.getId());
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр",
                new BigDecimal("483.50"), null, true, false);

        WorkActResponse before = workActService.get(act.id(), owner.getId());
        assertThat(before.receiptsTotal()).isEqualByComparingTo("0.00"); // itemized = reference-only
        assertThat(before.payable()).isEqualByComparingTo("7733.50");    // 7 250 + 483.50, once

        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ObjectEconomyResponse economy = objectExpenseService.economy(p.getId(), owner.getId());
        assertThat(economy.acts().contracted()).isEqualByComparingTo("14983.50");    // 14 500 + позиції
        assertThat(economy.acts().acceptedByActs()).isEqualByComparingTo("7733.50"); // роботи + позиції
        assertThat(economy.acts().acceptedByActs()).isLessThanOrEqualTo(economy.acts().contracted());
        // The master's own spend is still real → one expense, from the receipt.
        assertThat(economy.internals().expenses()).isEqualByComparingTo("483.50");
    }

    @Test
    void receiptsOnlyAct_isSignable() throws Exception {
        // «Фінальний акт з матеріалами»: an act that bills nothing but re-billed receipts is
        // legitimate content (round 2 — the empty guard counts receipts too).
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр",
                new BigDecimal("2400.00"), null, false, false);

        WorkActResponse signed = workActService.signOffline(
                act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThat(signed.status()).isEqualTo(WorkActStatus.SIGNED);
        assertThat(signed.payable()).isEqualByComparingTo("2400.00");
    }

    @Test
    void receiptAdd_requiresAPhotoAndSaneFields() {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        WorkActResponse act = createInterim(p.getId(), owner.getId());

        assertThatThrownBy(() -> receiptService.add(act.id(), owner.getId(), null, "Епіцентр",
                new BigDecimal("100.00"), null, false, false))
                .isInstanceOf(WorkActValidationException.class); // no photo
        assertThatThrownBy(() -> receiptService.add(act.id(), owner.getId(), receiptPhoto(), "  ",
                new BigDecimal("100.00"), null, false, false))
                .isInstanceOf(WorkActValidationException.class); // blank label
        assertThatThrownBy(() -> receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр",
                new BigDecimal("-5.00"), null, false, false))
                .isInstanceOf(WorkActValidationException.class); // negative → 400, not a DB 500
    }

    @Test
    void addendumEstimate_cannotBeExcludedFromTheEconomy() throws Exception {
        // The rollup's being counted is one half of «Прийнято ⊆ За договором» — unticking it would
        // push the ratio past 100 % (economy review). 409.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID line = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(line, est.getId(), ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("10.000")),
                new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж", null,
                        Unit.M2, new BigDecimal("500.00"), new BigDecimal("2.000")))), owner.getId());
        WorkActResponse signed = workActService.signOffline(
                act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> estimateService.setCountInEconomy(
                signed.addendumEstimateId(), false, owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void saveToPhotos_filesACopyIntoTheReceiptsFolder() throws Exception {
        // The act keeps ITS frozen copy; the gallery gets an independent one in «Чеки» — deleting
        // or re-filing the gallery copy can never touch the signed document (photo-folders).
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        WorkActResponse act = createInterim(p.getId(), owner.getId());

        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр",
                new BigDecimal("483.50"), null, false, true);

        var photos = photoRepository.findByProjectIdOrderByCreatedAtDesc(p.getId());
        assertThat(photos).singleElement().satisfies(photo -> {
            assertThat(photo.getFolder()).isEqualTo(com.majstr.backend.entity.ProjectPhoto.FOLDER_RECEIPTS);
            assertThat(photo.getSource()).isEqualTo(com.majstr.backend.entity.PhotoSource.RECEIPT);
            assertThat(photo.getCaption()).isEqualTo("Епіцентр");
        });
    }

    /** A minimal valid JPEG upload — the photo became mandatory in round 2. */
    private static org.springframework.mock.web.MockMultipartFile receiptPhoto() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        return new org.springframework.mock.web.MockMultipartFile("file", "receipt.jpg", "image/jpeg", jpeg);
    }

    /** Arrange helper: DRAFT → SENT without dragging the share/portal machinery into these tests. */
    private void markSent(UUID actId) {
        WorkAct entity = workActRepository.findById(actId).orElseThrow();
        entity.setStatus(WorkActStatus.SENT);
        entity.setSentAt(java.time.Instant.now());
        workActRepository.save(entity);
    }

    @Test
    void exceedsEstimateFlaggedWhenOverTheEstimateQuantity() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        WorkActResponse withItems = setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "120.000", "145.00");

        assertThat(withItems.items().get(0).exceedsEstimate()).isTrue();
    }

    @Test
    void signedActIsImmutable_andOnlyDraftOrRejectedDeletable() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "50.000", "145.00");
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "10.000", "145.00"))
                .isInstanceOf(WorkActSignedException.class);
        assertThatThrownBy(() -> workActService.delete(act.id(), owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void signingWithAdditionalWorks_createsSignedAddendumEstimate() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        // A line with NO estimateItemId = additional work not in any estimate.
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж стіни", null,
                        Unit.M2, new BigDecimal("500.00"), new BigDecimal("3.000")))), owner.getId());
        WorkActResponse signed = workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThat(signed.addendumEstimateId()).isNotNull();
        Estimate addendum = estimateRepository.findById(signed.addendumEstimateId()).orElseThrow();
        assertThat(addendum.getKind()).isEqualTo(EstimateKind.ADDENDUM);
        assertThat(addendum.getStatus()).isEqualTo(EstimateStatus.SIGNED);
        assertThat(addendum.isCountInEconomy()).isTrue();
        assertThat(addendum.isPortalVisible()).isFalse();
        assertThat(estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(addendum.getId()))
                .singleElement()
                .satisfies(i -> assertThat(i.getLineTotal()).isEqualByComparingTo("1500.00")); // 500 × 3

        // The economy panel for the rollup carries its kind (economy-review) — the PWA badges it
        // so it doesn't read as a кошторис the master forgot creating.
        assertThat(objectExpenseService.economy(p.getId(), owner.getId()).estimates())
                .anySatisfy(panel -> {
                    assertThat(panel.kind()).isEqualTo(EstimateKind.ADDENDUM);
                    assertThat(panel.total()).isEqualByComparingTo("1500.00");
                });
    }

    @Test
    void finalActClosesTheObject() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse fin = workActService.create(p.getId(),
                new WorkActCreateRequest(WorkActKind.FINAL, null, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), owner.getId(), null);
        setSingleLine(fin.id(), owner.getId(), est.getId(), lineId, "100.000", "145.00");
        workActService.signOffline(fin.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> createInterim(p.getId(), owner.getId()))
                .isInstanceOf(WorkActConflictException.class);
    }

    @Test
    void reopeningTheParentEstimate_isBlockedOnceActsAreSigned() throws Exception {
        // Supersedes the earlier "reopen leaves the act intact" contract (review fix): the act's
        // frozen lines DID survive, but the economy did not — a reopened estimate drops out of «За
        // договором» (SIGNED-only) while its act lines keep counting in «Прийнято актами», and once
        // DRAFT it can even be deleted. So reopen is refused outright while SIGNED acts reference
        // the estimate; the master creates a separate estimate for new positions instead.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "60.000", "145.00");
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> estimateService.reopen(est.getId(), owner.getId()))
                .isInstanceOf(WorkActConflictException.class);

        // The signed act (and the estimate's signature) are exactly as they were.
        WorkActResponse reloaded = workActService.get(act.id(), owner.getId());
        assertThat(reloaded.status()).isEqualTo(WorkActStatus.SIGNED);
        assertThat(estimateRepository.findById(est.getId()).orElseThrow().getStatus())
                .isEqualTo(EstimateStatus.SIGNED);
    }

    @Test
    void reopeningWithOnlyAnOpenAct_isStillAllowed() {
        // A DRAFT/SENT act is editable, so it can absorb an estimate change — only SIGNED acts
        // freeze the estimate (review fix keeps reopen available until the first signature).
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "60.000", "145.00");

        estimateService.reopen(est.getId(), owner.getId());

        assertThat(estimateRepository.findById(est.getId()).orElseThrow().getStatus())
                .isEqualTo(EstimateStatus.DRAFT);
    }

    @Test
    void duplicatingAnEstimate_isBlockedOnceActsAreSigned() throws Exception {
        // duplicate() excludes the SOURCE from the economy on the spot — with signed acts against
        // it, «Прийнято актами» would silently lose those works and the picker would forget them
        // (the copy's lines are new ids with done=0). Same family as the reopen guard.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "60.000", "145.00");
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        assertThatThrownBy(() -> estimateService.duplicate(est.getId(),
                new com.majstr.backend.dto.EstimateDuplicateRequest(null, new BigDecimal("10"), false, null),
                owner.getId()))
                .isInstanceOf(WorkActConflictException.class);

        // The source stayed in the economy — the failed duplicate must not have flipped it.
        assertThat(estimateRepository.findById(est.getId()).orElseThrow().isCountInEconomy()).isTrue();
    }

    @Test
    void duplicatingWithOnlyAnOpenAct_isStillAllowed() {
        // Mirrors the reopen rule: an open DRAFT/SENT act is editable and can absorb the change —
        // only a SIGNED act freezes the estimate.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), lineId, "60.000", "145.00");

        assertThat(estimateService.duplicate(est.getId(),
                new com.majstr.backend.dto.EstimateDuplicateRequest(null, new BigDecimal("10"), false, null),
                owner.getId())).isNotNull();
    }

    @Test
    void emptyActCannotBeSignedOffline() throws Exception {
        // A SIGNED act is immutable and undeletable — an empty one (worse: an empty FINAL) would be
        // permanent junk that also blocks future acts, so signing requires at least one line.
        User owner = newOwner();
        Project p = newProject(owner);
        signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        WorkActResponse act = createInterim(p.getId(), owner.getId());

        assertThatThrownBy(() ->
                workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId()))
                .isInstanceOf(WorkActValidationException.class);
    }

    @Test
    void lineFromAnotherProjectsEstimate_rejected() {
        // The project pin (review fix): SIGNED+counted alone is not enough — the referenced item
        // must belong to THIS act's object, or any known item UUID would pass the write path.
        User owner = newOwner();
        Project mine = newProject(owner);
        Project other = newProject(owner);
        signedEstimateWithLine(mine, "Робота", "100.000", "145.00");
        Estimate foreign = signedEstimateWithLine(other, "Чужа робота", "50.000", "200.00");
        UUID foreignLine = estimateItemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(foreign.getId()).get(0).getId();

        WorkActResponse act = createInterim(mine.getId(), owner.getId());
        assertThatThrownBy(() ->
                setSingleLine(act.id(), owner.getId(), foreign.getId(), foreignLine, "10.000", "200.00"))
                .isInstanceOf(WorkActValidationException.class);
    }

    @Test
    void estimateIdOnActLines_isDerivedFromTheItem_notTheRequest() {
        // A null (or wrong) client-sent estimateId must not land the line in the unconditional
        // «IS NULL» branch of sumSignedActLineTotals (review fix) — the server re-derives it.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID lineId = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();
        WorkActResponse act = createInterim(p.getId(), owner.getId());

        WorkActResponse withItems = workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(lineId, null, ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("10.000")))), owner.getId());

        assertThat(withItems.items()).singleElement()
                .satisfies(i -> assertThat(i.estimateId()).isEqualTo(est.getId()));
    }

    @Test
    void idempotentReplay_requiresTheActsOwner() {
        // The X-Entity-Uuid replay path must be as owner-scoped as the create it stands in for
        // (review fix): replaying another master's act UUID + project UUID returns nothing.
        User victim = newOwner();
        Project victimProject = newProject(victim);
        signedEstimateWithLine(victimProject, "Робота", "100.000", "145.00");
        UUID actId = UUID.randomUUID();
        workActService.create(victimProject.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), victim.getId(), actId);
        User attacker = newOwner();

        assertThatThrownBy(() -> workActService.create(victimProject.getId(),
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), attacker.getId(), actId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void excludedEstimate_isNotOfferedInThePicker_andCannotBeClosedByAnAct() {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate counted = signedEstimateWithLine(p, "Лічена", "100.000", "145.00", true);
        Estimate excluded = signedEstimateWithLine(p, "Виключена", "50.000", "200.00", false);
        UUID excludedLine = estimateItemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(excluded.getId()).get(0).getId();

        // The picker offers only the counted estimate's lines.
        ActProgressResponse progress = workActService.progress(p.getId(), owner.getId());
        assertThat(progress.lines()).isNotEmpty()
                .allSatisfy(l -> assertThat(l.estimateId()).isEqualTo(counted.getId()));

        // Defense-in-depth: the write path rejects a line from the excluded estimate.
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        assertThatThrownBy(() ->
                setSingleLine(act.id(), owner.getId(), excluded.getId(), excludedLine, "10.000", "200.00"))
                .isInstanceOf(WorkActValidationException.class);
    }

    @Test
    void acceptedByActs_countsCountedAndAdditionalOnly_neverExceedingContracted() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate counted = signedEstimateWithLine(p, "Лічена", "100.000", "145.00", true); // 14 500
        signedEstimateWithLine(p, "Виключена", "50.000", "200.00", false); // 10 000, excluded from economy
        UUID countedLine = estimateItemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(counted.getId()).get(0).getId();

        // Act closes 50 of the counted line (7 250) + an additional work (500 × 2 = 1 000).
        WorkActResponse act = createInterim(p.getId(), owner.getId());
        workActService.replaceItems(act.id(), new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(countedLine, counted.getId(), ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal("145.00"), new BigDecimal("50.000")),
                new WorkActItemsRequest.Line(null, null, ItemType.WORK, "Демонтаж", null,
                        Unit.M2, new BigDecimal("500.00"), new BigDecimal("2.000")))), owner.getId());
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ObjectEconomyResponse economy = objectExpenseService.economy(p.getId(), owner.getId());
        // contracted = counted estimate (14 500) + the additional-works ADDENDUM (1 000) = 15 500;
        // the excluded estimate (10 000) is in NEITHER axis.
        assertThat(economy.acts().contracted()).isEqualByComparingTo("15500.00");
        // acceptedByActs = counted line 7 250 + additional 1 000 = 8 250 — the excluded estimate's
        // work never enters the numerator, so the numerator stays within the denominator.
        assertThat(economy.acts().acceptedByActs()).isEqualByComparingTo("8250.00");
        assertThat(economy.acts().acceptedByActs()).isLessThanOrEqualTo(economy.acts().contracted());
    }

    // ---- receipts & invoices («Чеки та рахунки») ---------------------------------

    @Test
    void receipts_areBilledOnTopOfTheWorks_andFrozenOnceSigned() throws Exception {
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00"); // 14 500
        UUID line = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), line, "50.000", "145.00"); // 7 250
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр, клей",
                new BigDecimal("2400.00"), LocalDate.now().minusDays(2), false, false);
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Нова Пошта",
                new BigDecimal("600.00"), null, false, false);

        WorkActResponse withReceipts = workActService.get(act.id(), owner.getId());
        assertThat(withReceipts.receipts()).hasSize(2);
        assertThat(withReceipts.receiptsTotal()).isEqualByComparingTo("3000.00");
        assertThat(withReceipts.total()).isEqualByComparingTo("7250.00");   // works only
        assertThat(withReceipts.payable()).isEqualByComparingTo("10250.00"); // works + receipts

        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        // Signed = immutable, receipts included — they are part of the hashed document.
        assertThatThrownBy(() -> receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Пізній чек",
                new BigDecimal("100.00"), null, false, false)).isInstanceOf(WorkActSignedException.class);
        assertThatThrownBy(() -> receiptService.delete(act.id(),
                withReceipts.receipts().getFirst().id(), owner.getId()))
                .isInstanceOf(WorkActSignedException.class);
    }

    @Test
    void signedReceipts_landInBothEconomyAxes_soAcceptedNeverExceedsContracted() throws Exception {
        // The invariant with receipts in play: ActAddendumCreator puts them into «За договором» and
        // sumSignedActReceipts adds them to «Прийнято актами» — both halves or the axis lies.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00"); // 14 500
        UUID line = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), line, "50.000", "145.00"); // 7 250
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр", new BigDecimal("2400.00"), null, false, false);
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ObjectEconomyResponse economy = objectExpenseService.economy(p.getId(), owner.getId());
        assertThat(economy.acts().contracted()).isEqualByComparingTo("16900.00");     // 14 500 + 2 400
        assertThat(economy.acts().acceptedByActs()).isEqualByComparingTo("9650.00");  // 7 250 + 2 400
        assertThat(economy.acts().acceptedByActs()).isLessThanOrEqualTo(economy.acts().contracted());
        // receiptsToExpenses defaults on: the pass-through money is booked so profit stays honest.
        assertThat(economy.internals().expenses()).isEqualByComparingTo("2400.00");
    }

    @Test
    void receiptsToExpensesOff_stillBillsTheClientButPostsNoExpense() throws Exception {
        // A master who already logs his receipts in the expense journal turns this off — otherwise
        // the same 2 400 would be subtracted from profit twice.
        User owner = newOwner();
        Project p = newProject(owner);
        Estimate est = signedEstimateWithLine(p, "Робота", "100.000", "145.00");
        UUID line = estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(est.getId()).get(0).getId();

        WorkActResponse act = createInterim(p.getId(), owner.getId());
        setSingleLine(act.id(), owner.getId(), est.getId(), line, "50.000", "145.00");
        receiptService.add(act.id(), owner.getId(), receiptPhoto(), "Епіцентр", new BigDecimal("2400.00"), null, false, false);
        workActService.updateHeader(act.id(), new com.majstr.backend.dto.WorkActUpdateRequest(
                WorkActKind.INTERIM, null, LocalDate.now(), LocalDate.now().minusDays(7), LocalDate.now(),
                null, null, null, null, null, false, null, null), owner.getId());
        workActService.signOffline(act.id(), new WorkActSignOfflineRequest("Клієнт"), owner.getId());

        ObjectEconomyResponse economy = objectExpenseService.economy(p.getId(), owner.getId());
        assertThat(economy.acts().acceptedByActs()).isEqualByComparingTo("9650.00");
        assertThat(economy.internals().expenses()).isEqualByComparingTo("0.00");
    }

    // ---- fixtures ---------------------------------------------------------------

    private WorkActResponse createInterim(UUID projectId, UUID ownerId) {
        return workActService.create(projectId,
                new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(), LocalDate.now().minusDays(7),
                        LocalDate.now(), null, null, null, null, null, null), ownerId, null);
    }

    private WorkActResponse setSingleLine(UUID actId, UUID ownerId, UUID estimateId, UUID estimateItemId,
                                          String qty, String price) {
        return workActService.replaceItems(actId, new WorkActItemsRequest(List.of(
                new WorkActItemsRequest.Line(estimateItemId, estimateId, ItemType.WORK, "Робота", null,
                        Unit.M2, new BigDecimal(price), new BigDecimal(qty)))), ownerId);
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
                .status(ProjectStatus.IN_PROGRESS).build());
    }

    private Estimate signedEstimateWithLine(Project project, String name, String qty, String price) {
        return signedEstimateWithLine(project, name, qty, price, true);
    }

    private Estimate signedEstimateWithLine(Project project, String name, String qty, String price, boolean counted) {
        Estimate est = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SIGNED).countInEconomy(counted).build());
        estimateItemRepository.save(EstimateItem.builder()
                .estimate(est).type(ItemType.WORK).name(name).unit(Unit.M2)
                .quantity(new BigDecimal(qty)).unitPrice(new BigDecimal(price))
                .lineTotal(new BigDecimal(qty).multiply(new BigDecimal(price)).setScale(2))
                .sortOrder(0).build());
        return est;
    }

    private WorkAct rawAct(User owner, Project project, String number, WorkActKind kind) {
        return WorkAct.builder()
                .userId(owner.getId()).project(project).number(number).kind(kind)
                .status(WorkActStatus.DRAFT).issuedAt(LocalDate.now())
                .periodFrom(LocalDate.now().minusDays(1)).periodTo(LocalDate.now()).build();
    }
}
