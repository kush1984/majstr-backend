package com.majstr.backend.integration;

import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.service.ProjectPortalService;
import com.majstr.backend.service.PublicEstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V202 — the master's choice of where the client's page shows «Зведення» reaches the page. */
class PortalSummaryPlacementIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectPortalService portalService;
    @Autowired PublicEstimateService publicService;

    private UUID owner;
    private UUID project;
    private String token;

    @BeforeEach
    void seed() {
        owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status) VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')",
                project, owner);
        UUID estimate = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', 'SENT')", estimate, project);
        token = "tok-" + UUID.randomUUID();
        jdbc.update("INSERT INTO estimate_share_links (id, estimate_id, token) VALUES (?, ?, ?)",
                UUID.randomUUID(), estimate, token);
    }

    @Test
    void atTheTopByDefaultAndAfterThePositionsOnceChosen_onALinkAlreadySent() {
        assertThat(publicService.view(token).project().summaryAtBottom()).isFalse();

        ProjectResponse answer = portalService.setSummaryAtBottom(project, true, owner);
        assertThat(answer.portalSummaryAtBottom()).isTrue();
        PublicEstimateView page = publicService.view(token);
        assertThat(page.project().summaryAtBottom()).isTrue();

        portalService.setSummaryAtBottom(project, false, owner);
        assertThat(publicService.view(token).project().summaryAtBottom()).isFalse();
    }

    @Test
    void thePageRendersTheSummaryInExactlyOnePlaceForEitherChoice() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/portal/index.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("${summaryAtBottom ? '' : renderSummary(data.estimates)}")
                .contains("${summaryAtBottom ? renderSummary(data.estimates) : ''}");
        // After the positions, before the photos.
        int sections = html.indexOf("renderSection(e, showSectionTotals)");
        int bottom = html.indexOf("${summaryAtBottom ? renderSummary(data.estimates) : ''}");
        int photos = html.indexOf("${renderPhotos(data.sharedPhotos)}", sections);
        assertThat(sections).isLessThan(bottom);
        assertThat(bottom).isLessThan(photos);
    }
}
