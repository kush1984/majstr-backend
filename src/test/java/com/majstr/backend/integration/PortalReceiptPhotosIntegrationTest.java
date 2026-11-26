package com.majstr.backend.integration;

import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.PublicPortalView;
import com.majstr.backend.service.PublicEstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A receipt shared with the client shows under ITS estimate only (owner, 2026-11-26: a receipt parsed
 * into estimate A was on estimate B's page too, in the object's «Фото об'єкта»). The object's own
 * photos stay object-wide; a receipt whose estimate was deleted is shown nowhere.
 */
class PortalReceiptPhotosIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired PublicEstimateService publicService;

    private UUID project;
    private UUID estimateA;
    private UUID estimateB;
    private UUID receiptA;
    private UUID progress;
    private UUID orphan;

    @BeforeEach
    void seed() {
        UUID owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')",
                project, owner);
        estimateA = estimate("Ванна");
        estimateB = estimate("Кухня");
        receiptA = photo("RECEIPT", estimateA, "Ванна");
        progress = photo("MANUAL", null, null);
        orphan = photo("RECEIPT", null, "Видалений кошторис"); // its estimate is gone: link NULL, name kept
    }

    private UUID estimate(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status, portal_visible) VALUES (?, ?, ?, 'SENT', TRUE)",
                id, project, name);
        return id;
    }

    private UUID photo(String source, UUID estimateId, String estimateName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_photo (id, project_id, storage_key, source, visibility, estimate_id,
                                           estimate_name_snapshot, created_at)
                VALUES (?, ?, ?, ?, 'SHARED', ?, ?, now())
                """, id, project, "photos/" + id + ".jpg", source, estimateId, estimateName);
        return id;
    }

    private String singleLink(UUID estimateId) {
        String token = "tok-" + UUID.randomUUID();
        jdbc.update("INSERT INTO estimate_share_links (id, estimate_id, token) VALUES (?, ?, ?)",
                UUID.randomUUID(), estimateId, token);
        return token;
    }

    private static List<UUID> ids(List<PublicEstimateView.SharedPhoto> photos) {
        return photos.stream().map(PublicEstimateView.SharedPhoto::id).toList();
    }

    @Test
    void anotherEstimatesPage_neverShowsTheReceipt() {
        PublicEstimateView b = publicService.view(singleLink(estimateB));

        assertThat(ids(b.sharedPhotos())).containsExactly(progress);
        assertThat(b.receiptPhotos()).isEmpty();
    }

    @Test
    void itsOwnEstimatesPage_showsItApartFromTheObjectsPhotos() {
        PublicEstimateView a = publicService.view(singleLink(estimateA));

        assertThat(ids(a.receiptPhotos())).containsExactly(receiptA);
        assertThat(ids(a.sharedPhotos())).containsExactly(progress);
    }

    @Test
    void theObjectPortal_putsTheReceiptUnderItsEstimate_andTheOrphanNowhere() {
        String token = "tok-" + UUID.randomUUID();
        jdbc.update("INSERT INTO project_share_links (id, project_id, token, kind) VALUES (?, ?, ?, 'PORTAL')",
                UUID.randomUUID(), project, token);

        PublicPortalView portal = publicService.viewPortal(token);

        assertThat(ids(portal.sharedPhotos())).containsExactly(progress);
        assertThat(portal.estimates()).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo(estimateA);
            assertThat(ids(s.receiptPhotos())).containsExactly(receiptA);
        });
        assertThat(portal.estimates()).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo(estimateB);
            assertThat(s.receiptPhotos()).isEmpty();
        });
        assertThat(portal.estimates().stream().flatMap(s -> s.receiptPhotos().stream()).map(PublicEstimateView.SharedPhoto::id))
                .doesNotContain(orphan);
    }

    @Test
    void thePageDrawsTheReceiptsInsideTheEstimateSection() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
        int section = html.indexOf("function renderSection(");
        assertThat(html.indexOf("${renderPhotos(est.receiptPhotos, '🧾 Чеки')}", section)).isGreaterThan(section);
        assertThat(html).contains("receiptPhotos: data.receiptPhotos"); // the single-estimate link too
    }
}
