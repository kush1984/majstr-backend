package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.CatalogItemsOrderRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock CatalogItemRepository catalogRepository;
    @Mock CatalogTemplateRepository catalogTemplateRepository;
    @Mock UserRepository userRepository;
    @Mock UserTradeRepository userTradeRepository;
    @InjectMocks CatalogService catalogService;

    private final UUID ownerId = UUID.randomUUID();

    @Test
    void create_trimsAndCollapsesCategory() {
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Кабель ВВГнг", "  Електро   роботи  ", null, null,
                        ItemType.MATERIAL, Unit.M, new BigDecimal("38.50")),
                ownerId);

        assertThat(resp.category()).isEqualTo("Електро роботи");
    }

    @Test
    void create_whenSameNameTypeUnitExists_updatesInsteadOfDuplicating() {
        CatalogItem existing = CatalogItem.builder()
                .id(UUID.randomUUID())
                .owner(User.builder().id(ownerId).build())
                .name("Кабель ВВГнг").category("Старе").trade(Trade.OTHER)
                .type(ItemType.MATERIAL).unit(Unit.M).defaultPrice(new BigDecimal("10.00"))
                .build();
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of(existing));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("  кабель ввгнг  ", "Електрика", Trade.ELECTRICAL, null,
                        ItemType.MATERIAL, Unit.M, new BigDecimal("42.00")),
                ownerId);

        // Matched by (name trimmed/case-insensitive, type, unit) → updated in place, no new row.
        assertThat(existing.getDefaultPrice()).isEqualByComparingTo("42.00");
        assertThat(existing.getCategory()).isEqualTo("Електрика");
        assertThat(existing.getTrade()).isEqualTo(Trade.ELECTRICAL);
        assertThat(resp.defaultPrice()).isEqualByComparingTo("42.00");
        verify(catalogRepository, never()).save(any());
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    void create_blankCategoryBecomesNull() {
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Розетка", "   ", null, null,
                        ItemType.WORK, Unit.PIECE, new BigDecimal("180.00")),
                ownerId);

        assertThat(resp.category()).isNull();
    }

    // ---- custom trades (user_trade) ------------------------------------------

    @Test
    void create_withCustomTrade_forcesTradeToOtherAndStoresTheLink() {
        UserTrade custom = UserTrade.builder().id(UUID.randomUUID()).name("Натяжні стелі").build();
        given(userTradeRepository.findByIdAndUserId(custom.getId(), ownerId)).willReturn(Optional.of(custom));
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Монтаж стелі", null, Trade.ELECTRICAL, custom.getId(),
                        ItemType.WORK, Unit.M2, new BigDecimal("250.00")),
                ownerId);

        // A custom trade always wins over any system trade sent alongside it — the invariant
        // both CatalogItem's own CHECK and EstimateTemplate's mirror pin (trade = OTHER).
        assertThat(resp.trade()).isEqualTo(Trade.OTHER);
        assertThat(resp.customTradeId()).isEqualTo(custom.getId());
        assertThat(resp.customTradeName()).isEqualTo("Натяжні стелі");
    }

    @Test
    void create_withCustomTradeNotOwnedByCaller_throwsNotFound() {
        UUID foreignCustomTradeId = UUID.randomUUID();
        given(userTradeRepository.findByIdAndUserId(foreignCustomTradeId, ownerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.create(
                new CatalogItemRequest("Монтаж стелі", null, null, foreignCustomTradeId,
                        ItemType.WORK, Unit.M2, new BigDecimal("250.00")),
                ownerId))
                .isInstanceOf(com.majstr.backend.exception.ResourceNotFoundException.class);
        verify(catalogRepository, never()).save(any());
    }

    @Test
    void update_switchingBackToASystemTrade_clearsTheCustomTradeLink() {
        UUID itemId = UUID.randomUUID();
        UserTrade custom = UserTrade.builder().id(UUID.randomUUID()).name("Натяжні стелі").build();
        CatalogItem existing = CatalogItem.builder()
                .id(itemId).owner(User.builder().id(ownerId).build())
                .name("Монтаж стелі").trade(Trade.OTHER).customTrade(custom)
                .type(ItemType.WORK).unit(Unit.M2).defaultPrice(new BigDecimal("250.00"))
                .build();
        given(catalogRepository.findById(itemId)).willReturn(Optional.of(existing));

        catalogService.update(itemId,
                new CatalogItemRequest("Монтаж стелі", null, Trade.ELECTRICAL, null,
                        ItemType.WORK, Unit.M2, new BigDecimal("250.00")),
                ownerId);

        assertThat(existing.getTrade()).isEqualTo(Trade.ELECTRICAL);
        assertThat(existing.getCustomTrade()).isNull();
    }

    // ---- offline authoring: client-provided id (X-Entity-Uuid) --------------

    @Test
    void create_withClientId_replayReturnsTheExistingItemInsteadOfDuplicating() {
        UUID id = UUID.randomUUID();
        CatalogItem existing = CatalogItem.builder()
                .id(id).owner(User.builder().id(ownerId).build())
                .name("Кабель ВВГнг").trade(Trade.OTHER)
                .type(ItemType.MATERIAL).unit(Unit.M).defaultPrice(new BigDecimal("38.50"))
                .build();
        given(catalogRepository.findById(id)).willReturn(Optional.of(existing));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Кабель ВВГнг", null, null, null,
                        ItemType.MATERIAL, Unit.M, new BigDecimal("38.50")),
                ownerId, id);

        assertThat(resp.id()).isEqualTo(id);
        verify(catalogRepository, never()).save(any());
        // The id check short-circuits BEFORE the (name, type, unit) scan.
        verify(catalogRepository, never()).findByOwnerIdOrderByNameAsc(any());
    }

    @Test
    void create_withClientId_deniesAnIdOwnedByAnotherUser() {
        UUID id = UUID.randomUUID();
        given(catalogRepository.findById(id)).willReturn(Optional.of(foreignItem(id)));

        assertThatThrownBy(() -> catalogService.create(
                new CatalogItemRequest("Hijack", null, null, null,
                        ItemType.WORK, Unit.PIECE, new BigDecimal("1.00")),
                ownerId, id))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_withClientId_firstTimeKeepsTheSuppliedId() {
        UUID id = UUID.randomUUID();
        given(catalogRepository.findById(id)).willReturn(Optional.empty());
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of());
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Розетка", null, null, null,
                        ItemType.WORK, Unit.PIECE, new BigDecimal("180.00")),
                ownerId, id);

        assertThat(resp.id()).isEqualTo(id);
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        UUID id = UUID.randomUUID();
        given(catalogRepository.findById(id)).willReturn(Optional.empty());

        catalogService.delete(id, ownerId); // replayed offline delete

        verify(catalogRepository, never()).delete(any(CatalogItem.class));
    }

    @Test
    void listForOwner_returnsTheMastersOwnArrangement_notAlphabeticalOrder() {
        // This test USED to assert category-then-name, and that contract was deliberately replaced
        // in V87. Alphabetical is still the DEFAULT — the migration backfilled exactly that into
        // sort_order, so nothing moved for anyone — but it stops being the truth the moment the
        // master drags something, and a list that re-sorts itself alphabetically afterwards would
        // simply throw his arrangement away on every read.
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of(
                ordered("Клей", "Плитка", 3),
                ordered("Розетка", null, 0),
                ordered("Кабель", "Електрика", 2),
                ordered("Автомат", "Електрика", 1)
        ));
        given(catalogTemplateRepository.findNameKeysSharedAcrossTrades()).willReturn(List.of());

        List<CatalogItemResponse> list = catalogService.listForOwner(ownerId, null);

        assertThat(list).extracting(CatalogItemResponse::name)
                .containsExactly("Розетка", "Автомат", "Кабель", "Клей");
    }

    @Test
    void listForOwner_marksAPositionSharedWithAnotherTrade_excludingItsOwnTrade() {
        // The row itself is filed under PAINTER (the trade that happened to claim the unique
        // (owner, name, type, unit) slot first — see the catalog_items unique index), but
        // catalog_templates ships the same name under both PAINTER and TILING. The trade filter
        // needs to know that, or this row is invisible the moment a master who runs both trades
        // selects the TILING chip.
        CatalogItem shared = item("Організаційні послуги", "Транспортні витрати за містом");
        shared.setTrade(Trade.PAINTER);
        shared.setType(ItemType.WORK);
        shared.setUnit(Unit.KM);
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of(shared));
        given(catalogTemplateRepository.findNameKeysSharedAcrossTrades()).willReturn(List.<Object[]>of(
                new Object[] {"транспортні витрати за містом", "WORK", "KM", "PAINTER,TILING"}
        ));

        List<CatalogItemResponse> list = catalogService.listForOwner(ownerId, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).trade()).isEqualTo(Trade.PAINTER);
        assertThat(list.get(0).sharedTrades()).containsExactly(Trade.TILING);
    }

    private CatalogItem ordered(String name, String category, int sortOrder) {
        CatalogItem i = item(category, name);
        i.setSortOrder(sortOrder);
        return i;
    }

    @Test
    void categories_returnsRepositoryDistinctList() {
        given(catalogRepository.findDistinctCategoriesByOwner(ownerId))
                .willReturn(List.of("Електрика", "Плитка"));

        assertThat(catalogService.categories(ownerId)).containsExactly("Електрика", "Плитка");
    }

    // ---- autocomplete search -----------------------------------------------

    @Test
    void search_blankQuery_returnsEmptyWithoutHittingRepo() {
        assertThat(catalogService.search(ownerId, "   ", null, 10)).isEmpty();
        verifyNoInteractions(catalogRepository);
    }

    @Test
    void search_buildsLoweredPatternAndPrefix_clampsLimit_andMapsResults() {
        given(catalogRepository.searchByOwner(eq(ownerId), eq(ItemType.WORK),
                eq("%плит%"), eq("плит%"), any(Pageable.class)))
                .willReturn(List.of(item("Плитка", "Укладання плитки")));

        List<CatalogItemResponse> res = catalogService.search(ownerId, "  Плит  ", ItemType.WORK, 999);

        assertThat(res).extracting(CatalogItemResponse::name).containsExactly("Укладання плитки");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(catalogRepository).searchByOwner(eq(ownerId), eq(ItemType.WORK),
                eq("%плит%"), eq("плит%"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(20); // limit clamped from 999
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    void search_noTypeFilter_passesNullType() {
        given(catalogRepository.searchByOwner(eq(ownerId), isNull(),
                eq("%кабель%"), eq("кабель%"), any(Pageable.class)))
                .willReturn(List.of());

        catalogService.search(ownerId, "Кабель", null, 5);

        verify(catalogRepository).searchByOwner(eq(ownerId), isNull(),
                eq("%кабель%"), eq("кабель%"), any(Pageable.class));
    }

    @Test
    void likePatternAndPrefixPattern_lowercaseTrimAndWrap() {
        assertThat(CatalogService.likePattern(null)).isNull();
        assertThat(CatalogService.likePattern("  ")).isNull();
        assertThat(CatalogService.likePattern("  Плитка ")).isEqualTo("%плитка%");
        assertThat(CatalogService.prefixPattern("  Плитка ")).isEqualTo("плитка%");
    }

    // ---- tenant isolation (ownership guard) --------------------------------

    @Test
    void listForOwner_queriesOnlyTheGivenOwnersItems() {
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of());
        given(catalogTemplateRepository.findNameKeysSharedAcrossTrades()).willReturn(List.of());

        catalogService.listForOwner(ownerId, null);

        // The list is owner-scoped at the query: never an unfiltered read.
        verify(catalogRepository).findByOwnerIdOrderByNameAsc(ownerId);
    }

    @Test
    void update_deniesItemOwnedByAnotherUser() {
        UUID itemId = UUID.randomUUID();
        CatalogItem foreign = foreignItem(itemId);
        given(catalogRepository.findById(itemId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> catalogService.update(itemId,
                new CatalogItemRequest("Hijack", null, null, null, ItemType.WORK, Unit.PIECE, new BigDecimal("1.00")),
                ownerId))
                .isInstanceOf(AccessDeniedException.class);

        // The guard throws before any field is mutated (update relies on JPA
        // dirty-checking, not save) — the other user's item is untouched.
        assertThat(foreign.getName()).isEqualTo("Чужа позиція");
    }

    @Test
    void delete_deniesItemOwnedByAnotherUser() {
        UUID itemId = UUID.randomUUID();
        given(catalogRepository.findById(itemId)).willReturn(Optional.of(foreignItem(itemId)));

        assertThatThrownBy(() -> catalogService.delete(itemId, ownerId))
                .isInstanceOf(AccessDeniedException.class);

        verify(catalogRepository, never()).delete(any(CatalogItem.class));
    }

    /** A catalog item belonging to a DIFFERENT user than {@link #ownerId}. */
    private CatalogItem foreignItem(UUID id) {
        return CatalogItem.builder()
                .id(id)
                .owner(User.builder().id(UUID.randomUUID()).build())
                .name("Чужа позиція")
                .type(ItemType.WORK)
                .unit(Unit.PIECE)
                .defaultPrice(new BigDecimal("100.00"))
                .build();
    }

    private CatalogItem item(String category, String name) {
        return CatalogItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .category(category)
                .type(ItemType.MATERIAL)
                .unit(Unit.M)
                .defaultPrice(new BigDecimal("1.00"))
                .build();
    }

    // ---- the master's own arrangement (V87) -------------------------------------------------

    private CatalogItem owned(String name, String category, int sortOrder) {
        CatalogItem i = item(category, name);
        i.setOwner(User.builder().id(ownerId).build());
        i.setSortOrder(sortOrder);
        return i;
    }

    @Test
    void reorderRenumbersFromZeroAndCarriesTheCategoryWithEachPosition() {
        CatalogItem a = owned("Ґрунтівка", "Підготовка", 0);
        CatalogItem b = owned("Плитка", "Укладання", 1);
        given(catalogRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId))
                .willReturn(List.of(a, b));

        catalogService.reorderItems(new CatalogItemsOrderRequest(List.of(
                new CatalogItemsOrderRequest.Line(b.getId(), "Укладання"),
                new CatalogItemsOrderRequest.Line(a.getId(), "Укладання"))), ownerId);

        assertThat(b.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        // Dragging INTO another group is the same operation — the position is re-filed, not just moved.
        assertThat(a.getCategory()).isEqualTo("Укладання");
    }

    @Test
    void aPositionTheRequestNeverMentionedKeepsItsPlaceAfterTheRest() {
        // The client can legitimately send a subset: a stale list, or a position created on another
        // device since. Dropping those would silently delete them from the order.
        CatalogItem listed = owned("Плитка", "Укладання", 0);
        CatalogItem unknownToTheClient = owned("Нова з іншого пристрою", "Укладання", 1);
        given(catalogRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId))
                .willReturn(List.of(listed, unknownToTheClient));

        catalogService.reorderItems(new CatalogItemsOrderRequest(List.of(
                new CatalogItemsOrderRequest.Line(listed.getId(), "Укладання"))), ownerId);

        assertThat(listed.getSortOrder()).isZero();
        assertThat(unknownToTheClient.getSortOrder()).isEqualTo(1);
    }

    @Test
    void anIdListedTwiceOrAlreadyGoneIsSkippedRatherThanCollidingOnASlot() {
        CatalogItem a = owned("Ґрунтівка", "Підготовка", 0);
        given(catalogRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId))
                .willReturn(List.of(a));

        catalogService.reorderItems(new CatalogItemsOrderRequest(List.of(
                new CatalogItemsOrderRequest.Line(a.getId(), "Підготовка"),
                new CatalogItemsOrderRequest.Line(a.getId(), "Підготовка"),
                new CatalogItemsOrderRequest.Line(UUID.randomUUID(), "Зникла"))), ownerId);

        assertThat(a.getSortOrder()).isZero();
    }

    @Test
    void bulkDeleteIsScopedToTheOwnerAndReportsWhatActuallyWent() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        // One of the two was already gone (another device, or a replayed offline delete).
        given(catalogRepository.deleteByOwnerIdAndIdIn(ownerId, ids)).willReturn(1);

        assertThat(catalogService.deleteItems(ids, ownerId)).isEqualTo(1);
    }

    @Test
    void bulkDeleteOfNothingTouchesTheDatabaseAtAll() {
        // `IN ()` is not valid SQL, and an empty tick-list is a normal thing for a screen to send.
        assertThat(catalogService.deleteItems(List.of(), ownerId)).isZero();
        verify(catalogRepository, never()).deleteByOwnerIdAndIdIn(any(), any());
    }
}
