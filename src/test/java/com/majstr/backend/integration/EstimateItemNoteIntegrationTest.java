package com.majstr.backend.integration;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.PublicEstimateItemView;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CatalogService;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.PublicEstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The master's own comment on an estimate line (V206): stored three-valued, read by the client unless
 * he switches it off, carried by a duplicate, and frozen into the signed snapshot only when visible.
 */
class EstimateItemNoteIntegrationTest extends IntegrationTestBase {

    @Autowired EstimateService estimateService;
    @Autowired CatalogService catalogService;
    @Autowired PublicEstimateService publicService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired EstimateShareLinkRepository shareLinkRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anAbsentNoteLeavesTheCommentAlone_blankClearsIt_andTheClientSeesItUnlessHidden() {
        Fixture f = sentEstimate();

        EstimateItemResponse noted = update(f, "  у 2 шари  ", null);
        assertThat(noted.note()).isEqualTo("у 2 шари");
        assertThat(noted.noteVisible()).isTrue();
        assertThat(clientLine(f).note()).isEqualTo("у 2 шари");

        // An inline price edit sends no note: the comment survives.
        EstimateItemResponse repriced = estimateService.updateItem(f.estimate.getId(), f.itemId,
                new EstimateItemRequest(ItemType.WORK, "Фарбування стін", null, Unit.M2, new BigDecimal("100.000"),
                        new BigDecimal("250.00"), null, null, false, null, null), f.ownerId);
        assertThat(repriced.note()).isEqualTo("у 2 шари");

        update(f, null, false);
        assertThat(clientLine(f).note()).as("hidden from the client").isNull();
        assertThat(itemRepository.findById(f.itemId).orElseThrow().getNote()).isEqualTo("у 2 шари");

        assertThat(update(f, " ", null).note()).as("blank clears").isNull();
    }

    @Test
    void aDuplicateCarriesTheComment_andItsVisibility() {
        Fixture f = sentEstimate();
        update(f, "матеріал замовника", false);

        UUID copyId = estimateService.duplicate(f.estimate.getId(),
                new EstimateDuplicateRequest(null, BigDecimal.ZERO, false, null), f.ownerId).id();

        EstimateItem copied = itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(copyId).getFirst();
        assertThat(copied.getNote()).isEqualTo("матеріал замовника");
        assertThat(copied.isNoteVisible()).isFalse();
    }

    @Test
    void theSignedSnapshotFreezesAVisibleComment_andNeverAHiddenOne() {
        Fixture visible = sentEstimate();
        update(visible, "без демонтажу", null);
        sign(visible);
        assertThat(snapshotOf(visible)).contains("\"note\":\"без демонтажу\"");
        assertThat(clientLine(visible).note()).as("rendered from the snapshot").isEqualTo("без демонтажу");

        Fixture hidden = sentEstimate();
        update(hidden, "собі: взяти більший шпатель", false);
        sign(hidden);
        assertThat(snapshotOf(hidden)).doesNotContain("більший шпатель");
    }

    @Test
    void aLineAddedFromTheCatalogCopiesItsComment() {
        Fixture f = sentEstimate();
        CatalogItemResponse position = catalogService.create(catalogRequest("Шпаклювання стін", "по сітці"), f.ownerId);

        EstimateItemResponse line = estimateService.addItemFromCatalog(f.estimate.getId(), position.id(),
                new EstimateItemFromCatalogRequest(new BigDecimal("10"), null), f.ownerId);

        assertThat(position.note()).isEqualTo("по сітці");
        assertThat(line.note()).isEqualTo("по сітці");
        assertThat(line.noteVisible()).isTrue();
    }

    @Test
    void aCatalogComment_reachesUnsignedEstimates_neverASignedOne_norALineHeChangedInPlace() {
        Fixture draft = sentEstimate();
        UUID ownerId = draft.ownerId;
        CatalogItemResponse position = catalogService.create(catalogRequest("Фарбування стін", null), ownerId);
        Fixture own = estimateOf(ownerId);
        Fixture signed = estimateOf(ownerId);
        // In this estimate he wrote his own comment.
        update(own, "у 3 шари", null);
        sign(signed);
        long versionBefore = estimateRepository.findById(draft.estimate.getId()).orElseThrow().getVersion();

        catalogService.update(position.id(), catalogRequest("Фарбування стін", "у 2 шари"), ownerId);

        assertThat(noteOf(draft)).isEqualTo("у 2 шари");
        assertThat(noteOf(own)).as("his own comment stays").isEqualTo("у 3 шари");
        assertThat(noteOf(signed)).as("a signed estimate is never rewritten").isNull();
        assertThat(estimateRepository.findById(draft.estimate.getId()).orElseThrow().getVersion())
                .as("the client must not sign the page he saw before").isGreaterThan(versionBefore);

        // An inline price edit sends no comment: nothing moves.
        catalogService.update(position.id(), new CatalogItemRequest("Фарбування стін", null, null, null,
                ItemType.WORK, Unit.M2, new BigDecimal("210.00")), ownerId);
        assertThat(noteOf(draft)).isEqualTo("у 2 шари");

        catalogService.update(position.id(), catalogRequest("Фарбування стін", ""), ownerId);
        assertThat(noteOf(draft)).as("cleared in the catalog, cleared on the line it gave it to").isNull();
        assertThat(noteOf(own)).isEqualTo("у 3 шари");
    }

    @Test
    void aCommentChangedInTheEstimateAndChangedBack_isStillHis() {
        Fixture f = sentEstimate();
        CatalogItemResponse position = catalogService.create(catalogRequest("Фарбування стін", null), f.ownerId);
        catalogService.update(position.id(), catalogRequest("Фарбування стін", "у 2 шари"), f.ownerId);
        assertThat(noteOf(f)).isEqualTo("у 2 шари");

        update(f, "у 3 шари", null);
        update(f, "у 2 шари", null);
        catalogService.update(position.id(), catalogRequest("Фарбування стін", "у 4 шари"), f.ownerId);

        assertThat(noteOf(f)).isEqualTo("у 2 шари");
    }

    @Test
    void theLibraryHintIsOneCommentToHim_editedOrClearedOnTheLine_andInTheCatalog() {
        Fixture f = sentEstimate();
        jdbc.update("UPDATE estimate_items SET description = 'Рівень Q3' WHERE id = ?", f.itemId);

        update(f, "Рівень Q3, без шліфування", null);
        EstimateItem line = itemRepository.findById(f.itemId).orElseThrow();
        assertThat(line.getNote()).isEqualTo("Рівень Q3, без шліфування");
        assertThat(line.getDescription()).as("his comment replaces the hint").isNull();

        update(f, "", null);
        line = itemRepository.findById(f.itemId).orElseThrow();
        assertThat(line.getNote()).isNull();
        assertThat(line.getDescription()).as("cleared, the hint does not come back").isNull();

        CatalogItemResponse position = catalogService.create(catalogRequest("Шліфування стиків", null), f.ownerId);
        jdbc.update("UPDATE catalog_items SET description = 'Рівень Q4' WHERE id = ?", position.id());
        catalogService.update(position.id(), new CatalogItemRequest("Шліфування стиків", null, null, null,
                ItemType.WORK, Unit.M2, new BigDecimal("210.00")), f.ownerId);
        assertThat(jdbc.queryForObject("SELECT description FROM catalog_items WHERE id = ?", String.class,
                position.id())).as("an edit that does not touch the comment keeps the hint").isEqualTo("Рівень Q4");

        CatalogItemResponse cleared = catalogService.update(position.id(), catalogRequest("Шліфування стиків", ""), f.ownerId);
        assertThat(cleared.note()).isNull();
        assertThat(cleared.description()).isNull();
    }

    @Test
    void aLineWhoseCommentNoLongerMatchesTheCatalogIsLeftAlone() {
        Fixture f = sentEstimate();
        CatalogItemResponse position = catalogService.create(catalogRequest("Фарбування стін", "у 2 шари"), f.ownerId);
        // Written straight into the row, as an import would: not «his own», but not the catalog's either.
        jdbc.update("UPDATE estimate_items SET note = 'з плінтусом' WHERE id = ?", f.itemId);

        catalogService.update(position.id(), catalogRequest("Фарбування стін", "у 3 шари"), f.ownerId);

        assertThat(noteOf(f)).isEqualTo("з плінтусом");
    }

    // ---- fixtures ---------------------------------------------------------------

    private record Fixture(UUID ownerId, Estimate estimate, UUID itemId, String token) {}

    private EstimateItemResponse update(Fixture f, String note, Boolean visible) {
        return estimateService.updateItem(f.estimate.getId(), f.itemId,
                new EstimateItemRequest(ItemType.WORK, "Фарбування стін", null, Unit.M2, new BigDecimal("100.000"),
                        new BigDecimal("200.00"), null, null, false, null, null, note, visible), f.ownerId);
    }

    private static CatalogItemRequest catalogRequest(String name, String note) {
        return new CatalogItemRequest(name, null, null, null, ItemType.WORK, Unit.M2, new BigDecimal("200.00"), note);
    }

    private String noteOf(Fixture f) {
        return itemRepository.findById(f.itemId).orElseThrow().getNote();
    }

    private PublicEstimateItemView clientLine(Fixture f) {
        return publicService.view(f.token).items().getFirst();
    }

    private void sign(Fixture f) {
        long version = estimateRepository.findById(f.estimate.getId()).orElseThrow().getVersion();
        publicService.sign(f.token, new SignRequest("Марія Петренко", "+380672222222", version), "203.0.113.42");
    }

    private String snapshotOf(Fixture f) {
        return jdbc.queryForObject("SELECT snapshot FROM signed_document WHERE estimate_id = ?", String.class,
                f.estimate.getId());
    }

    private Fixture sentEstimate() {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test").passwordHash("x")
                .fullName("Майстер").phone("+380000000000").companyName("ФОП")
                .plan(Plan.PRO)
                .referralCode(unique.substring(0, 10)).build());
        return estimateOf(owner.getId());
    }

    private Fixture estimateOf(UUID ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        Project project = projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1")
                .status(ProjectStatus.IN_PROGRESS).build());
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(project).status(EstimateStatus.SENT).countInEconomy(true).build());
        EstimateItem item = itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(ItemType.WORK).name("Фарбування стін").unit(Unit.M2)
                .quantity(new BigDecimal("100.000")).unitPrice(new BigDecimal("200.00"))
                .lineTotal(new BigDecimal("20000.00")).sortOrder(0).build());
        EstimateShareLink link = shareLinkRepository.save(EstimateShareLink.builder()
                .estimate(estimate).token("tok-" + UUID.randomUUID()).build());
        return new Fixture(owner.getId(), estimate, item.getId(), link.getToken());
    }
}
