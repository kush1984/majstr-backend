package com.majstr.backend.service;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
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
    @Mock UserRepository userRepository;
    @InjectMocks CatalogService catalogService;

    private final UUID ownerId = UUID.randomUUID();

    @Test
    void create_trimsAndCollapsesCategory() {
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Кабель ВВГнг", "  Електро   роботи  ", null,
                        ItemType.MATERIAL, Unit.M, new BigDecimal("38.50")),
                ownerId);

        assertThat(resp.category()).isEqualTo("Електро роботи");
    }

    @Test
    void create_blankCategoryBecomesNull() {
        given(userRepository.getReferenceById(ownerId)).willReturn(User.builder().id(ownerId).build());
        given(catalogRepository.save(any(CatalogItem.class))).willAnswer(inv -> inv.getArgument(0));

        CatalogItemResponse resp = catalogService.create(
                new CatalogItemRequest("Розетка", "   ", null,
                        ItemType.WORK, Unit.PIECE, new BigDecimal("180.00")),
                ownerId);

        assertThat(resp.category()).isNull();
    }

    @Test
    void listForOwner_groupsByCategoryThenName_withUncategorizedLast() {
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of(
                item("Плитка", "Клей"),
                item(null, "Розетка"),
                item("Електрика", "Кабель"),
                item("Електрика", "Автомат")
        ));

        List<CatalogItemResponse> list = catalogService.listForOwner(ownerId, null);

        // Електрика (Автомат, Кабель) -> Плитка (Клей) -> "Без категорії" (Розетка)
        assertThat(list).extracting(CatalogItemResponse::name)
                .containsExactly("Автомат", "Кабель", "Клей", "Розетка");
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
                new CatalogItemRequest("Hijack", null, null, ItemType.WORK, Unit.PIECE, new BigDecimal("1.00")),
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
}
