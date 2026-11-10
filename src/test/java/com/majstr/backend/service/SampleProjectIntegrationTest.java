package com.majstr.backend.service;

import com.majstr.backend.dto.ActivationFunnelResponse;
import com.majstr.backend.dto.DashboardMetricsResponse;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.dto.WorkActCreateRequest;
import com.majstr.backend.dto.WorkActResponse;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.exception.SampleProjectExistsException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.integration.IntegrationTestBase;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The training sample (V197) against real Postgres: what it contains, that the FREE cap never sees it,
 * that it is deleted at any stage and made again, that no figure reads it — signed, sent, paid and
 * printed — and that its act takes «П-N», outside the master's own numbering.
 *
 * <p>The schema is shared across the run, so the counters are read as DELTAS around this test's own
 * master.</p>
 */
class SampleProjectIntegrationTest extends IntegrationTestBase {

    @Autowired SampleProjectService sampleProjectService;
    @Autowired ProjectService projectService;
    @Autowired LimitService limitService;
    @Autowired MetricsService metricsService;
    @Autowired DashboardService dashboardService;
    @Autowired ClientHandoffs handoffs;
    @Autowired WorkActService workActService;
    @Autowired UserRepository userRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository estimateItemRepository;
    @Autowired CatalogTemplateRepository libraryRepository;
    @Autowired EstimateService estimateService;
    @Autowired PublicEstimateService publicEstimateService;
    @Autowired PortalPreviewTokens previewTokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everySampleLineStillFindsAPricedLibraryPosition() {
        List<SampleObjects.Sample> samples = new ArrayList<>(SampleObjects.BY_TRADE.values());
        samples.add(SampleObjects.BATHROOM);
        List<String> missing = new ArrayList<>();
        for (SampleObjects.Sample sample : samples) {
            Map<String, CatalogTemplate> library = libraryRepository
                    .findByTradeInOrderBySortOrderAsc(List.of(sample.trade())).stream()
                    .collect(Collectors.toMap(r -> NameKeys.of(r.getName()) + "|" + r.getUnit(), r -> r, (a, b) -> a));
            for (SampleObjects.Line line : sample.lines()) {
                CatalogTemplate row = library.get(NameKeys.of(line.name()) + "|" + line.unit());
                if (row == null || row.getSuggestedPrice() == null || row.getSuggestedPrice().signum() <= 0) {
                    missing.add(sample.trade() + ": " + line.name() + " (" + line.unit() + ")");
                }
            }
        }
        // A renamed or zero-priced library row would silently drop a line, or show the client 0 ₴.
        assertThat(missing).isEmpty();
    }

    @Test
    void theSampleIsAWholePricedEstimate_outsideTheCap_andThereIsOnlyOne() {
        User owner = master(Trade.TILING);
        long usedBefore = limitService.limitsFor(owner.getId()).projectsUsed();

        ProjectResponse sample = sampleProjectService.create(owner.getId(), null);

        assertThat(sample.sample()).isTrue();
        assertThat(sample.name()).isEqualTo(SampleObjects.BATHROOM.title());
        List<EstimateItem> items = itemsOf(sample.id());
        assertThat(items).hasSize(SampleObjects.BATHROOM.lines().size());
        assertThat(items).allSatisfy(i -> {
            assertThat(i.getUnitPrice()).isPositive();
            assertThat(i.getQuantity()).isPositive();
        });
        // The cap counts every object ever made (V107) — not this one.
        assertThat(limitService.limitsFor(owner.getId()).projectsUsed()).isEqualTo(usedBefore);
        assertThatThrownBy(() -> sampleProjectService.create(owner.getId(), Trade.PLUMBING))
                .isInstanceOf(SampleProjectExistsException.class);
    }

    @Test
    void whatTheClientWouldReadOfTheSample_saysItIsATrainingSample() {
        User owner = master(Trade.TILING);
        ProjectResponse sample = sampleProjectService.create(owner.getId(), null);
        UUID estimateId = estimateRepository.findByProjectIdOrderByCreatedAtDesc(sample.id()).get(0).getId();

        assertThat(publicEstimateService.viewPreview(previewTokens.issue(estimateId)).project().sample()).isTrue();
        assertThat(publicEstimateService.viewPortalPreview(previewTokens.issuePortal(sample.id(), List.of(estimateId)))
                .project().sample()).isTrue();
    }

    @Test
    void theTradeJustPickedChoosesTheSample() {
        User owner = master(Trade.TILING);

        ProjectResponse sample = sampleProjectService.create(owner.getId(), Trade.PLUMBING);

        assertThat(sample.name()).isEqualTo(SampleObjects.BY_TRADE.get(Trade.PLUMBING).title());
    }

    @Test
    void aSignedPaidSample_isDeletedFreely_andCanBeMadeAgain() {
        User owner = master(Trade.TILING);
        ProjectResponse sample = sampleProjectService.create(owner.getId(), null);
        signAll(sample.id());
        jdbc.update("""
                INSERT INTO payment_receipt (id, project_id, label, amount, received_at, created_at, material_refund)
                VALUES (?, ?, 'Завдаток', 1000, ?, now(), false)
                """, UUID.randomUUID(), sample.id(), LocalDate.now());

        // A real object with a signature and money is archived, never deleted (B-70) — the sample is.
        projectService.delete(sample.id(), owner.getId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id = ?", Long.class, sample.id())).isZero();
        assertThat(sampleProjectService.create(owner.getId(), null).sample()).isTrue();
    }

    @Test
    void noFigureReadsTheSample_signedSentAndPrinted() throws Exception {
        User owner = master(Trade.TILING);
        ActivationFunnelResponse funnelBefore = metricsService.activationFunnel();
        DashboardMetricsResponse dashBefore = dashboardService.metrics(owner.getId());

        ProjectResponse sample = sampleProjectService.create(owner.getId(), null);
        UUID estimateId = estimateRepository.findByProjectIdOrderByCreatedAtDesc(sample.id()).get(0).getId();
        jdbc.update("UPDATE estimates SET status = 'SENT' WHERE id = ?", estimateId);
        signAll(sample.id());
        // The owner's PDF door — the one that writes a MASTER row for a real object.
        byte[] pdf = estimateService.renderPdf(estimateId, owner.getId());
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        Thread.sleep(300); // the PDF log is asynchronous; a sample must leave no row at all

        ActivationFunnelResponse funnelAfter = metricsService.activationFunnel();
        assertThat(funnelAfter.withProject()).isEqualTo(funnelBefore.withProject());
        assertThat(funnelAfter.withEstimate()).isEqualTo(funnelBefore.withEstimate());
        assertThat(funnelAfter.withSigned()).isEqualTo(funnelBefore.withSigned());
        assertThat(handoffs.handedToClient()).doesNotContainKey(owner.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_render WHERE owner_id = ?", Long.class,
                owner.getId())).isZero();
        assertThat(dashboardService.metrics(owner.getId())).isEqualTo(dashBefore);
    }

    @Test
    void theSamplesActIsNumberedPN_andTheMastersOwnSequenceStartsAtOne() {
        User owner = master(Trade.TILING);
        ProjectResponse sample = sampleProjectService.create(owner.getId(), null);
        signAll(sample.id());

        WorkActResponse sampleAct = workActService.create(sample.id(), interim(), owner.getId(), null);
        assertThat(sampleAct.number()).isEqualTo("П-1");

        UUID realProject = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, owner_id, name, status) VALUES (?, ?, 'Справжній', 'IN_PROGRESS')",
                realProject, owner.getId());
        jdbc.update("""
                INSERT INTO estimates (id, project_id, status, kind, created_at, updated_at, version, count_in_economy)
                VALUES (?, ?, 'SIGNED', 'REGULAR', now(), now(), 0, true)
                """, UUID.randomUUID(), realProject);
        WorkActResponse realAct = workActService.create(realProject, interim(), owner.getId(), null);
        assertThat(realAct.number()).isEqualTo("1");
    }

    private static WorkActCreateRequest interim() {
        return new WorkActCreateRequest(WorkActKind.INTERIM, null, LocalDate.now(),
                LocalDate.now().minusDays(7), LocalDate.now(), null, null, null, null, null, null);
    }

    private void signAll(UUID projectId) {
        jdbc.update("UPDATE estimates SET status = 'SIGNED' WHERE project_id = ?", projectId);
    }

    private List<EstimateItem> itemsOf(UUID projectId) {
        UUID estimateId = estimateRepository.findByProjectIdOrderByCreatedAtDesc(projectId).get(0).getId();
        return estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId);
    }

    private User master(Trade trade) {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(unique + "@majstr.test").emailCanonical(unique + "@majstr.test").passwordHash("x")
                .fullName("Майстер Приклад").plan(Plan.FREE).role(Role.USER)
                .referralCode(unique.substring(0, 10))
                .build());
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, ?)", owner.getId(), trade.name());
        return owner;
    }
}
