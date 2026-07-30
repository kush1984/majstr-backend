package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogInsights;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.repository.CatalogInsightDismissalRepository;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * The three lists that turn master behaviour into a verdict on our own catalog.
 *
 * <p>The distinction under test throughout is the one the feature exists for: a master's
 * position that we ALREADY ship (however they spelled it) says our wording is wrong, while one
 * we do not ship says our coverage is short. Getting that split backwards would make the whole
 * screen advice-shaped noise.</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminCatalogInsightsServiceTest {

    @Mock CatalogItemRepository catalogItemRepository;
    @Mock CatalogTemplateRepository templateRepository;
    @Mock EstimateItemRepository estimateItemRepository;
    @Mock CatalogInsightDismissalRepository dismissalRepository;
    @InjectMocks AdminCatalogInsightsService service;

    /** Minimal stub of the aggregated-row projection. */
    private record Row(String name, String type, String unit, String category, long masters,
                       Instant firstSeen, BigDecimal medianPrice,
                       BigDecimal minPrice, BigDecimal maxPrice)
            implements CatalogItemRepository.MasterPositionRow {
        @Override public String getName() { return name; }
        @Override public String getType() { return type; }
        @Override public String getUnit() { return unit; }
        @Override public String getCategory() { return category; }
        @Override public long getMasters() { return masters; }
        @Override public Instant getFirstSeen() { return firstSeen; }
        @Override public BigDecimal getMedianPrice() { return medianPrice; }
        @Override public BigDecimal getMinPrice() { return minPrice; }
        @Override public BigDecimal getMaxPrice() { return maxPrice; }
    }

    private static Row row(String name, long masters) {
        return new Row(name, "WORK", "M2", "Стіни", masters, Instant.parse("2026-05-01T00:00:00Z"),
                new BigDecimal("200"), new BigDecimal("150"), new BigDecimal("260"));
    }

    private static CatalogTemplate ours(String name) {
        return CatalogTemplate.builder()
                .id(UUID.randomUUID()).trade(Trade.BUILDER).category("Стіни").name(name)
                .type(ItemType.WORK).unit(Unit.M2).suggestedPrice(new BigDecimal("180"))
                .addedInVersion(1).build();
    }

    @Test
    void aPositionWeDoNotShipIsAGap_andTheMostCorroboratedComesFirst() {
        given(templateRepository.findAll()).willReturn(List.of(ours("Укладання плитки")));
        given(catalogItemRepository.aggregateMasterPositions()).willReturn(List.of(
                row("Монтаж інфрачервоної плівки", 3),
                row("Демонтаж підвіконня", 11)));

        List<CatalogInsights.NewPosition> out = service.newPositions();

        // Eleven masters independently typing the same thing is a far better default candidate
        // than three, whatever the dates say — that ordering IS the recommendation.
        assertThat(out).extracting(CatalogInsights.NewPosition::name)
                .containsExactly("Демонтаж підвіконня", "Монтаж інфрачервоної плівки");
        assertThat(out.get(0).masters()).isEqualTo(11);
    }

    @Test
    void aSynonymIsNOTdetected_andThatBoundaryIsDeliberate() {
        // The honest limit of this feature. «Штукатурка стін» and «Оштукатурювання поверхонь
        // стін цементно-піщаним розчином» are the same job, and no normalisation can know
        // that — they share one word. Catching it needs synonymy, i.e. judgement.
        //
        // So it surfaces as a GAP, not as a wording problem, and the admin recognises it while
        // reviewing. That is the correct failure direction: a candidate a human looks at, rather
        // than a silent merge of two things that only a model thought were alike.
        given(templateRepository.findAll())
                .willReturn(List.of(ours("Оштукатурювання поверхонь стін цементно-піщаним розчином")));
        given(catalogItemRepository.aggregateMasterPositions())
                .willReturn(List.of(row("Штукатурка стін", 7)));

        assertThat(service.rewordedPositions()).isEmpty();
        assertThat(service.newPositions()).extracting(CatalogInsights.NewPosition::name)
                .containsExactly("Штукатурка стін");
    }

    @Test
    void therewordedListPairsOurNameWithTheirs() {
        CatalogTemplate our = ours("Гідроізоляція покрівлі (мастика), євроруберойд");
        given(templateRepository.findAll()).willReturn(List.of(our));
        given(catalogItemRepository.aggregateMasterPositions())
                .willReturn(List.of(row("Гідроізоляція покрівлі мастика євроруберойд", 4)));

        List<CatalogInsights.RenamedPosition> out = service.rewordedPositions();

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.ourName()).isEqualTo(our.getName());
            assertThat(r.theirName()).isEqualTo("Гідроізоляція покрівлі мастика євроруберойд");
            assertThat(r.ourTemplateId()).isEqualTo(our.getId());
        });
    }

    @Test
    void aSeededCopyIsNotAFinding() {
        // Identical text: the master simply received our position. Reporting it would bury the
        // real signal under the entire default catalog, once per master.
        given(templateRepository.findAll()).willReturn(List.of(ours("Укладання плитки")));
        given(catalogItemRepository.aggregateMasterPositions())
                .willReturn(List.of(row("Укладання плитки", 40)));

        assertThat(service.rewordedPositions()).isEmpty();
        assertThat(service.newPositions()).isEmpty();
    }

    @Test
    void aDefaultNothingResemblesIsReportedAsUnused_andFlaggedApproximate() {
        given(templateRepository.findAll())
                .willReturn(List.of(ours("Монтаж рейкового карниза"), ours("Укладання плитки")));
        given(estimateItemRepository.aggregateUsedNames()).willReturn(List.of(
                new UsedRow("укладання плитки", 12)));

        List<CatalogInsights.UnusedDefault> out = service.unusedDefaults();

        assertThat(out).singleElement().satisfies(u -> {
            assertThat(u.name()).isEqualTo("Монтаж рейкового карниза");
            // The flag is part of the contract: estimate lines are snapshots with no FK to the
            // catalog, so this can never be more than a name match. It must not be read as a
            // usage statistic.
            assertThat(u.approximate()).isTrue();
        });
    }

    @Test
    void usageIsMatchedThroughTheSameNormalisation_notExactText() {
        given(templateRepository.findAll()).willReturn(List.of(ours("Демонтаж плитки настінної")));
        given(estimateItemRepository.aggregateUsedNames())
                .willReturn(List.of(new UsedRow("демонтаж настінної плитки", 3)));

        assertThat(service.unusedDefaults()).isEmpty();
    }

    @Test
    void aDismissedCandidateStopsComingBack() {
        given(templateRepository.findAll()).willReturn(List.of());
        given(catalogItemRepository.aggregateMasterPositions())
                .willReturn(List.of(row("Демонтаж підвіконня", 11)));
        given(dismissalRepository.findByKind(CatalogInsightKind.NEW_POSITION)).willReturn(List.of(
                com.majstr.backend.entity.CatalogInsightDismissal.builder()
                        .kind(CatalogInsightKind.NEW_POSITION)
                        .nameKey(com.majstr.backend.service.catalog.CatalogNameKey.of("Демонтаж підвіконня"))
                        .sampleName("Демонтаж підвіконня").build()));

        assertThat(service.newPositions()).isEmpty();
    }

    private record UsedRow(String name, long uses) implements EstimateItemRepository.UsedNameRow {
        @Override public String getName() { return name; }
        @Override public long getUses() { return uses; }
    }
}
