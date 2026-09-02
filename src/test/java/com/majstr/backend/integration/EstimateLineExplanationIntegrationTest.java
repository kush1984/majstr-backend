package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.PublicEstimateItemView;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateTemplateService;
import com.majstr.backend.service.PublicEstimateService;
import com.majstr.backend.service.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V119 — a catalog position's explanation travels with the line, all the way to the client.
 *
 * <p>Master feedback on the three finishing levels V116 shipped: «якщо таке попаде в кошторис,
 * звідки клієнт має знати що це таке? … а в порталі клієнта і на пдф — розшифрування тих
 * позначень». The explanation already existed on the catalog; what did not exist was any path
 * from there to the document the client reads.</p>
 *
 * <p>Why a real database rather than Mockito: the field is a COLUMN that must survive a round trip
 * (V119 has to have applied, and a 500-char explanation has to fit), and the two paths that copy
 * it — picking a position straight from the catalog, and applying a template bundle whose lines
 * are joined to the catalog by name — both read rows the mocks would otherwise hand back
 * pre-built. The template path is the one that would silently regress: a bundle carries no
 * explanation of its own, so the value has to come from the name match, and a name match that
 * misses fails by producing {@code null} rather than by throwing.</p>
 */
class EstimateLineExplanationIntegrationTest extends IntegrationTestBase {

    private static final String Q4 = "Підготовка ГКЛ під фарбування · Q4 (еліт)";
    private static final String Q4_MEANS =
            "Найвищий рівень: суцільне шпаклювання, поверхня під глянцеву фарбу та бокове світло.";

    @Autowired JdbcTemplate jdbc;
    @Autowired EstimateService estimateService;
    @Autowired EstimateTemplateService templateService;
    @Autowired PublicEstimateService publicEstimateService;
    @Autowired ShareLinkService shareLinkService;
    @Autowired EstimateRepository estimateRepository;

    private UUID ownerId;
    private UUID projectId;
    private UUID catalogId;
    private UUID templateId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'DRYWALL')", ownerId);

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        catalogId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, description, type, unit,
                                           default_price, trade, source, sort_order)
                VALUES (?, ?, ?, 'Оздоблення під фарбування', ?, 'WORK', 'M2', 260.00,
                        'DRYWALL', 'LIBRARY', 0)
                """, catalogId, ownerId, Q4, Q4_MEANS);
        // A second position, deliberately without an explanation: most work needs none, and a line
        // that invented one would put words in the master's mouth on a document the client signs.
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (?, ?, 'Грунтування', 'Підготовка та захист', 'WORK', 'M2', 33.00,
                        'DRYWALL', 'LIBRARY', 1)
                """, UUID.randomUUID(), ownerId);

        templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES (?, ?, 'Мій набір', 'DRYWALL', FALSE)
                """, templateId, ownerId);
        templateItem(Q4, 0);
        templateItem("Грунтування", 1);
    }

    private void templateItem(String name, int sort) {
        jdbc.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (?, ?, ?, 'WORK', 'M2', ?)
                """, UUID.randomUUID(), templateId, name, sort);
    }

    private UUID newEstimate() {
        return estimateService
                .createForProject(projectId, new EstimateCreateRequest(null, null, "Кошторис"), ownerId)
                .id();
    }

    @Test
    void pickingThePositionFromTheCatalogCarriesItsExplanationOntoTheLine() {
        UUID estimateId = newEstimate();

        var line = estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0), ownerId, null);

        assertThat(line.description()).isEqualTo(Q4_MEANS);
        assertThat(jdbc.queryForObject(
                "SELECT description FROM estimate_items WHERE id = ?", String.class, line.id()))
                .as("stored, not computed on read — the client signed this wording")
                .isEqualTo(Q4_MEANS);
    }

    @Test
    void applyingABundleTakesTheExplanationFromTheCatalogPositionItPricedFrom() {
        // A template carries a name and nothing else — no price, no explanation. Both are resolved
        // from the master's own catalog by the SAME name key, so a line priced from a position is
        // also explained by it.
        EstimateResponse estimate = templateService.applyToProject(projectId, templateId,
                new EstimateCreateRequest(null, null, "З шаблону"), ownerId);

        assertThat(estimate.items())
                .extracting(i -> i.name() + " → " + i.description())
                .containsExactly(Q4 + " → " + Q4_MEANS, "Грунтування → null");
    }

    @Test
    void theClientPortalCarriesNoExplanationAtAll() {
        // The field started life as a client-facing one and is now master-only: «по порталі і пдф —
        // … приберемо для клієнта взагалі покищо, йому це не треба». It is still STORED on the line
        // (the master reads it in the app), so the guard is that the public view has no room for it.
        UUID estimateId = newEstimate();
        estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0), ownerId, null);
        String token = shareLinkService.create(estimateId, ownerId).token();

        var view = publicEstimateService.view(token);

        assertThat(view.items()).singleElement()
                .extracting(PublicEstimateItemView::name).isEqualTo(Q4);
        assertThat(PublicEstimateItemView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("description");
    }

    @Test
    void aSignedEstimateKeepsTheWordingItWasSignedWithEvenAfterTheCatalogIsReworded() {
        UUID estimateId = newEstimate();
        var line = estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0), ownerId, null);
        var estimate = estimateRepository.findById(estimateId).orElseThrow();
        estimate.setStatus(EstimateStatus.SIGNED);
        estimateRepository.save(estimate);

        jdbc.update("UPDATE catalog_items SET description = 'Щось зовсім інше' WHERE id = ?", catalogId);

        assertThat(jdbc.queryForObject(
                "SELECT description FROM estimate_items WHERE id = ?", String.class, line.id()))
                .isEqualTo(Q4_MEANS);
    }

    @Test
    void duplicatingAnEstimateBringsTheExplanationsAlong() {
        UUID estimateId = newEstimate();
        estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0), ownerId, null);

        var copy = estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, BigDecimal.ZERO, false, List.of()), ownerId);

        assertThat(copy.items()).singleElement()
                .extracting(i -> i.description()).isEqualTo(Q4_MEANS);
    }
}
