package com.majstr.backend.service.importer;

import com.majstr.backend.dto.DictationCommitRequest;
import com.majstr.backend.dto.DictationParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.EstimateSignedException;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.service.EstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The rule this flow lives or dies by: a position we could not pin to the master's own catalog
 * comes back FLAGGED, never quietly priced at 0 UAH. A spoken number always beats the catalog; the
 * catalog fills in everything that was not said.
 */
@ExtendWith(MockitoExtension.class)
class DictationServiceTest {

    @Mock private DictationExtractor extractor;
    @Mock private CatalogItemRepository catalogItemRepository;
    @Mock private EstimateService estimateService;

    private DictationService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    private final CatalogItem wallpaper = CatalogItem.builder()
            .id(UUID.randomUUID())
            .name("Поклейка шпалер")
            .category("Шпалери")
            .unit(Unit.M2)
            .type(ItemType.WORK)
            .defaultPrice(new BigDecimal("150.00"))
            .build();

    @BeforeEach
    void setUp() {
        service = new DictationService(extractor, catalogItemRepository, estimateService);
    }

    @Test
    void parse_rejectsSignedEstimate_neverCallsTheModel() {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.SIGNED));

        assertThatThrownBy(() -> service.parse(ownerId, estimateId, "поклеїти шпалери"))
                .isInstanceOf(EstimateSignedException.class);
        verify(extractor, never()).extract(any());
    }

    @Test
    void aMatchedPosition_takesItsNameUnitPriceAndCategoryFromTheCatalog() {
        draftWithCatalog(List.of(wallpaper));
        given(extractor.extract(any())).willReturn(List.of(
                new DictationExtractor.Spoken("поклеїти шпалери", null, new BigDecimal("20"), null, "WORK")));

        DictationParseResponse resp = service.parse(ownerId, estimateId, "поклеїти шпалери двадцять квадратів");

        assertThat(resp.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Поклейка шпалер");   // his own wording, not the spoken one
            assertThat(item.spokenName()).isEqualTo("поклеїти шпалери"); // he still sees what he said
            assertThat(item.unit()).isEqualTo(Unit.M2);
            assertThat(item.unitPrice()).isEqualByComparingTo("150.00");
            assertThat(item.category()).isEqualTo("Шпалери");
            assertThat(item.catalogItemId()).isEqualTo(wallpaper.getId());
            assertThat(item.issues()).isEmpty();
        });
    }

    @Test
    void anUnmatchedPosition_isFlagged_neverSilentlyPricedAtZero() {
        // The whole reason "catalog" is an issue token: a 0 UAH line nobody notices is a line the
        // master signs for nothing.
        draftWithCatalog(List.of(wallpaper));
        given(extractor.extract(any())).willReturn(List.of(
                new DictationExtractor.Spoken("демонтаж старої плитки", "м2", new BigDecimal("12"), null, "WORK")));

        DictationParseResponse resp = service.parse(ownerId, estimateId, "демонтаж старої плитки 12 квадратів");

        assertThat(resp.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("демонтаж старої плитки"); // his words, nothing matched
            assertThat(item.catalogItemId()).isNull();
            assertThat(item.unitPrice()).isNull();                       // NOT BigDecimal.ZERO
            assertThat(item.unit()).isEqualTo(Unit.M2);                  // he did say the unit
            assertThat(item.issues()).containsExactly("catalog", "price");
        });
    }

    @Test
    void aSpokenPriceWins_overTheCatalogOne() {
        // A price he said out loud is a decision, not a guess, and must not be overwritten by the
        // price list.
        draftWithCatalog(List.of(wallpaper));
        given(extractor.extract(any())).willReturn(List.of(
                new DictationExtractor.Spoken("поклеїти шпалери", "м2", new BigDecimal("20"),
                        new BigDecimal("250"), "WORK")));

        DictationParseResponse resp = service.parse(ownerId, estimateId, "twenty square metres at 250");

        assertThat(resp.items().getFirst().unitPrice()).isEqualByComparingTo("250");
        assertThat(resp.items().getFirst().catalogItemId()).isEqualTo(wallpaper.getId()); // still matched
    }

    @Test
    void aQuantityNobodySaid_isAskedFor_notAssumedToBeOne() {
        draftWithCatalog(List.of(wallpaper));
        given(extractor.extract(any())).willReturn(List.of(
                new DictationExtractor.Spoken("поклеїти шпалери", null, null, null, "WORK")));

        DictationParseResponse resp = service.parse(ownerId, estimateId, "поклеїти шпалери");

        assertThat(resp.items().getFirst().quantity()).isNull();
        assertThat(resp.items().getFirst().issues()).containsExactly("quantity");
    }

    @Test
    void textWithNoPositions_returnsNothingAndNeverTouchesTheCatalog() {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(extractor.extract(any())).willReturn(List.of());

        assertThat(service.parse(ownerId, estimateId, "добрий день").items()).isEmpty();
        verify(catalogItemRepository, never()).findByOwnerIdOrderByNameAsc(any());
    }

    @Test
    void anUntypedLine_defaultsToWork_theOppositeOfAReceipt() {
        // A contractor dictating an estimate is listing his work; a receipt is goods.
        draftWithCatalog(List.of());
        given(extractor.extract(any())).willReturn(List.of(
                new DictationExtractor.Spoken("вирівнювання підлоги", "м2", new BigDecimal("30"), null, null)));

        assertThat(service.parse(ownerId, estimateId, "floor levelling").items().getFirst().type())
                .isEqualTo(ItemType.WORK);
    }

    @Test
    void commit_appendsMappedItemsToEstimate() {
        EstimateResponse updated = estimate(EstimateStatus.DRAFT);
        given(estimateService.appendItems(eq(estimateId), anyList(), eq(ownerId))).willReturn(updated);

        EstimateResponse resp = service.commit(ownerId, estimateId, new DictationCommitRequest(List.of(
                new DictationCommitRequest.CommitItem("Поклейка шпалер", Unit.M2, new BigDecimal("20"),
                        new BigDecimal("150"), ItemType.WORK, "Шпалери"))));

        assertThat(resp).isSameAs(updated);
        verify(estimateService).appendItems(eq(estimateId), anyList(), eq(ownerId));
    }

    // ---- helpers ----------------------------------------------------------

    private void draftWithCatalog(List<CatalogItem> catalog) {
        given(estimateService.get(estimateId, ownerId)).willReturn(estimate(EstimateStatus.DRAFT));
        given(catalogItemRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(catalog);
    }

    private EstimateResponse estimate(EstimateStatus status) {
        BigDecimal zero = BigDecimal.ZERO;
        return new EstimateResponse(estimateId, projectId, "Кошторис", status,
                null, null, null, Instant.now(), Instant.now(), List.of(), zero, zero, zero, null, zero, List.of());
    }
}
