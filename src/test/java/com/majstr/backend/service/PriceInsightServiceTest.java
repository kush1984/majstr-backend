package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogInsights;
import com.majstr.backend.entity.CatalogInsightDismissal;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.CatalogUpdateNotice;
import com.majstr.backend.entity.CatalogUpdateNoticeKind;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PriceInsightCandidate;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogInsightDismissalRepository;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.CatalogUpdateNoticeRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.PriceInsightCandidateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PriceInsightServiceTest {

    @Mock EstimateItemRepository estimateItemRepository;
    @Mock CatalogTemplateRepository templateRepository;
    @Mock CatalogItemRepository catalogItemRepository;
    @Mock PriceInsightCandidateRepository candidateRepository;
    @Mock CatalogInsightDismissalRepository dismissalRepository;
    @Mock CatalogUpdateNoticeRepository noticeRepository;

    private PriceInsightService service() {
        return new PriceInsightService(estimateItemRepository, templateRepository, catalogItemRepository,
                candidateRepository, dismissalRepository, noticeRepository);
    }

    /** Minimal stub of the per-master aggregated-row projection. */
    private record Row(UUID masterId, String rawKey, String name, String category, String type,
                        String unit, BigDecimal perMasterMedianPrice, Instant firstSeen)
            implements EstimateItemRepository.PerMasterPriceRow {
        @Override public UUID getMasterId() { return masterId; }
        @Override public String getRawKey() { return rawKey; }
        @Override public String getName() { return name; }
        @Override public String getCategory() { return category; }
        @Override public String getType() { return type; }
        @Override public String getUnit() { return unit; }
        @Override public BigDecimal getPerMasterMedianPrice() { return perMasterMedianPrice; }
        @Override public Instant getFirstSeen() { return firstSeen; }
    }

    private static Row row(UUID master, String name, String price) {
        return new Row(master, name.toLowerCase(java.util.Locale.ROOT), name, "Стіни", "WORK", "M2",
                new BigDecimal(price), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static CatalogTemplate template(String name, String price) {
        return CatalogTemplate.builder().id(UUID.randomUUID()).trade(Trade.BUILDER)
                .name(name).type(ItemType.WORK).unit(Unit.M2).suggestedPrice(new BigDecimal(price))
                .addedInVersion(1).build();
    }

    // ---- weeklyRefresh: PRICE_DRIFT vs NEW_POSITION ---------------------------------------

    @Test
    void weeklyRefresh_positionMatchingADefault_becomesAPriceDriftCandidate() {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID(), m3 = UUID.randomUUID();
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of(
                row(m1, "Штукатурка стін", "240"),
                row(m2, "Штукатурка стін", "250"),
                row(m3, "Штукатурка стін", "260")));
        given(templateRepository.findAll()).willReturn(List.of(template("Штукатурка стін", "200")));

        service().weeklyRefresh();

        List<PriceInsightCandidate> saved = savedOfKind(CatalogInsightKind.PRICE_DRIFT);
        assertThat(saved).singleElement().satisfies(c -> {
            assertThat(c.getKind()).isEqualTo(CatalogInsightKind.PRICE_DRIFT);
            assertThat(c.getProposedPrice()).isEqualByComparingTo("250");
            assertThat(c.getCurrentDefaultPrice()).isEqualByComparingTo("200");
            assertThat(c.getMastersCount()).isEqualTo(3);
        });
    }

    @Test
    void weeklyRefresh_positionWithNoDefault_becomesANewPositionCandidate() {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID(), m3 = UUID.randomUUID();
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of(
                row(m1, "Шпаклювання стель", "100"),
                row(m2, "Шпаклювання стель", "110"),
                row(m3, "Шпаклювання стель", "120")));
        given(templateRepository.findAll()).willReturn(List.of());

        service().weeklyRefresh();

        assertThat(savedOfKind(CatalogInsightKind.NEW_POSITION)).singleElement().satisfies(c -> {
            assertThat(c.getKind()).isEqualTo(CatalogInsightKind.NEW_POSITION);
            assertThat(c.getCatalogTemplateId()).isNull();
            assertThat(c.getCurrentDefaultPrice()).isNull();
            assertThat(c.getProposedPrice()).isEqualByComparingTo("110");
        });
    }

    @Test
    void weeklyRefresh_fewerThanThreeMasters_isDroppedEntirely() {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID();
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of(
                row(m1, "Рідкісна робота", "500"),
                row(m2, "Рідкісна робота", "520")));
        given(templateRepository.findAll()).willReturn(List.of());

        PriceInsightService.RefreshSummary summary = service().weeklyRefresh();

        assertThat(summary.rejectedForLowN()).isEqualTo(1);
        assertThat(summary.priceDrift()).isZero();
        assertThat(summary.newPosition()).isZero();
    }

    @Test
    void weeklyRefresh_oneMasterWithTwoSpellingVariants_stillCountsAsExactlyOneVote() {
        // The SQL step groups by EXACT lowercased name, so if the SAME master typed two
        // spellings that fuzzy-normalise to the same key ("Демонтаж плитки" vs "демонтаж,
        // плитки!" — punctuation-only difference), both survive as separate rows out of SQL.
        // The cross-master fold here (grouping by masterId before the median) must still count
        // him exactly once, or a master who typed a position two ways would out-vote someone
        // who typed it once.
        UUID prolific = UUID.randomUUID(), other1 = UUID.randomUUID(), other2 = UUID.randomUUID();
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of(
                row(prolific, "Демонтаж плитки", "100"),
                row(prolific, "демонтаж, плитки!", "140"), // same master, different spelling
                row(other1, "Демонтаж плитки", "500"),
                row(other2, "Демонтаж плитки", "520")));
        given(templateRepository.findAll()).willReturn(List.of());

        service().weeklyRefresh();

        assertThat(savedOfKind(CatalogInsightKind.NEW_POSITION)).singleElement()
                .extracting(PriceInsightCandidate::getMastersCount).isEqualTo(3); // not 4
    }

    @Test
    void weeklyRefresh_aDismissedPriceDriftKeyIsSkipped() {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID(), m3 = UUID.randomUUID();
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of(
                row(m1, "Штукатурка стін", "240"), row(m2, "Штукатурка стін", "250"),
                row(m3, "Штукатурка стін", "260")));
        given(templateRepository.findAll()).willReturn(List.of(template("Штукатурка стін", "200")));
        given(dismissalRepository.findByKind(CatalogInsightKind.PRICE_DRIFT)).willReturn(List.of(
                CatalogInsightDismissal.builder().kind(CatalogInsightKind.PRICE_DRIFT)
                        .nameKey(com.majstr.backend.service.catalog.CatalogNameKey.of("Штукатурка стін"))
                        .sampleName("Штукатурка стін").build()));

        PriceInsightService.RefreshSummary summary = service().weeklyRefresh();

        assertThat(summary.priceDrift()).isZero();
    }

    @Test
    void weeklyRefresh_alwaysReplacesBothQueuesRegardlessOfWhatSurvives() {
        given(estimateItemRepository.aggregatePerMasterWorkPrices()).willReturn(List.of());
        given(templateRepository.findAll()).willReturn(List.of());

        service().weeklyRefresh();

        verify(candidateRepository).deleteAllByKind(CatalogInsightKind.PRICE_DRIFT);
        verify(candidateRepository).deleteAllByKind(CatalogInsightKind.NEW_POSITION);
    }

    /** weeklyRefresh() always calls saveAll() twice — once per kind, the other list empty when
     *  nothing of that kind survived. Captures both invocations and returns the non-empty one
     *  matching {@code kind} (empty list if that kind had nothing to save). */
    @SuppressWarnings("unchecked")
    private List<PriceInsightCandidate> savedOfKind(CatalogInsightKind kind) {
        ArgumentCaptor<List<PriceInsightCandidate>> captor = ArgumentCaptor.forClass(List.class);
        verify(candidateRepository, times(2)).saveAll(captor.capture());
        // Empty lists vacuously match any "allMatch(kind)" check — excluded explicitly, or an
        // empty PRICE_DRIFT list captured before a non-empty NEW_POSITION one would shadow it.
        return captor.getAllValues().stream()
                .filter(list -> !list.isEmpty() && list.stream().allMatch(c -> c.getKind() == kind))
                .findFirst().orElse(List.of());
    }

    // ---- applyPriceDrift -------------------------------------------------------------------

    @Test
    void applyPriceDrift_updatesTheTemplateAndNotifiesOnlyEligibleLibraryMasters() {
        UUID candidateId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CatalogTemplate template = CatalogTemplate.builder().id(templateId).trade(Trade.BUILDER)
                .name("Штукатурка стін").type(ItemType.WORK).unit(Unit.M2)
                .suggestedPrice(new BigDecimal("200")).addedInVersion(1).build();
        PriceInsightCandidate candidate = PriceInsightCandidate.builder()
                .id(candidateId).kind(CatalogInsightKind.PRICE_DRIFT)
                .nameKey(com.majstr.backend.service.catalog.CatalogNameKey.of("Штукатурка стін"))
                .sampleName("Штукатурка стін").catalogTemplateId(templateId)
                .currentDefaultPrice(new BigDecimal("200")).proposedPrice(new BigDecimal("250"))
                .mastersCount(3).minPrice(new BigDecimal("240")).maxPrice(new BigDecimal("260"))
                .firstSeen(Instant.now()).build();
        given(candidateRepository.findById(candidateId)).willReturn(Optional.of(candidate));
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));

        User eligibleOwner = User.builder().id(UUID.randomUUID()).build();
        CatalogItem eligible = CatalogItem.builder().owner(eligibleOwner).name("Штукатурка стін")
                .source(CatalogItemSource.LIBRARY).defaultPrice(new BigDecimal("200")).build();
        given(catalogItemRepository.findLibraryItemsAtPrice(new BigDecimal("200")))
                .willReturn(List.of(eligible));

        service().applyPriceDrift(candidateId, UUID.randomUUID());

        assertThat(template.getSuggestedPrice()).isEqualByComparingTo("250");
        // addedInVersion untouched — an edit must not re-propagate as if newly added.
        assertThat(template.getAddedInVersion()).isEqualTo(1);

        ArgumentCaptor<CatalogUpdateNotice> noticeCaptor = ArgumentCaptor.forClass(CatalogUpdateNotice.class);
        verify(noticeRepository).save(noticeCaptor.capture());
        CatalogUpdateNotice saved = noticeCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(eligibleOwner.getId());
        assertThat(saved.getKind()).isEqualTo(CatalogUpdateNoticeKind.PRICE_DRIFT);
        assertThat(saved.getPositionName()).isEqualTo("Штукатурка стін");
        assertThat(saved.getOldPrice()).isEqualByComparingTo("200");
        assertThat(saved.getNewPrice()).isEqualByComparingTo("250");

        verify(candidateRepository).delete(candidate);
    }

    @Test
    void applyPriceDrift_skipsANameThatOnlyCoincidentallySharesTheOldPrice() {
        // findLibraryItemsAtPrice is a cheap SQL pre-filter on price alone; the fuzzy name match
        // must still happen in Java, or an unrelated position at the same old price gets a
        // notice that names the WRONG work.
        UUID candidateId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CatalogTemplate template = CatalogTemplate.builder().id(templateId).trade(Trade.BUILDER)
                .name("Штукатурка стін").type(ItemType.WORK).unit(Unit.M2)
                .suggestedPrice(new BigDecimal("200")).addedInVersion(1).build();
        PriceInsightCandidate candidate = PriceInsightCandidate.builder()
                .id(candidateId).kind(CatalogInsightKind.PRICE_DRIFT)
                .nameKey(com.majstr.backend.service.catalog.CatalogNameKey.of("Штукатурка стін"))
                .sampleName("Штукатурка стін").catalogTemplateId(templateId)
                .currentDefaultPrice(new BigDecimal("200")).proposedPrice(new BigDecimal("250"))
                .mastersCount(3).minPrice(new BigDecimal("240")).maxPrice(new BigDecimal("260"))
                .firstSeen(Instant.now()).build();
        given(candidateRepository.findById(candidateId)).willReturn(Optional.of(candidate));
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));

        CatalogItem unrelatedSamePrice = CatalogItem.builder()
                .owner(User.builder().id(UUID.randomUUID()).build())
                .name("Демонтаж плінтуса") // different work, same old price — pure coincidence
                .source(CatalogItemSource.LIBRARY).defaultPrice(new BigDecimal("200")).build();
        given(catalogItemRepository.findLibraryItemsAtPrice(new BigDecimal("200")))
                .willReturn(List.of(unrelatedSamePrice));

        service().applyPriceDrift(candidateId, UUID.randomUUID());

        verify(noticeRepository, never()).save(any());
    }

    @Test
    void applyPriceDrift_rejectsANewPositionCandidate_thereIsNoTemplateToApplyTo() {
        UUID candidateId = UUID.randomUUID();
        PriceInsightCandidate candidate = PriceInsightCandidate.builder()
                .id(candidateId).kind(CatalogInsightKind.NEW_POSITION)
                .nameKey("k").sampleName("X").proposedPrice(new BigDecimal("100"))
                .mastersCount(3).minPrice(new BigDecimal("90")).maxPrice(new BigDecimal("110"))
                .firstSeen(Instant.now()).build();
        given(candidateRepository.findById(candidateId)).willReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service().applyPriceDrift(candidateId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void applyPriceDrift_multipleEligibleMasters_eachGetsTheirOwnNoticeRow() {
        UUID candidateId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CatalogTemplate template = CatalogTemplate.builder().id(templateId).trade(Trade.BUILDER)
                .name("Штукатурка стін").type(ItemType.WORK).unit(Unit.M2)
                .suggestedPrice(new BigDecimal("200")).addedInVersion(1).build();
        PriceInsightCandidate candidate = PriceInsightCandidate.builder()
                .id(candidateId).kind(CatalogInsightKind.PRICE_DRIFT)
                .nameKey(com.majstr.backend.service.catalog.CatalogNameKey.of("Штукатурка стін"))
                .sampleName("Штукатурка стін").catalogTemplateId(templateId)
                .currentDefaultPrice(new BigDecimal("200")).proposedPrice(new BigDecimal("250"))
                .mastersCount(3).minPrice(new BigDecimal("240")).maxPrice(new BigDecimal("260"))
                .firstSeen(Instant.now()).build();
        given(candidateRepository.findById(candidateId)).willReturn(Optional.of(candidate));
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(catalogItemRepository.findLibraryItemsAtPrice(new BigDecimal("200"))).willReturn(List.of(
                CatalogItem.builder().owner(User.builder().id(UUID.randomUUID()).build())
                        .name("Штукатурка стін").source(CatalogItemSource.LIBRARY)
                        .defaultPrice(new BigDecimal("200")).build(),
                CatalogItem.builder().owner(User.builder().id(UUID.randomUUID()).build())
                        .name("штукатурка стін") // different case, same normalised key
                        .source(CatalogItemSource.LIBRARY).defaultPrice(new BigDecimal("200")).build()));

        service().applyPriceDrift(candidateId, UUID.randomUUID());

        verify(noticeRepository, times(2)).save(any());
    }
}
