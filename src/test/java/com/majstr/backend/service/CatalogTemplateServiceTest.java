package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.CatalogTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CatalogTemplateServiceTest {

    @Mock CatalogTemplateRepository templateRepository;
    @Mock CatalogItemRepository catalogRepository;
    @InjectMocks CatalogTemplateService catalogTemplateService;

    @Test
    void seedForUser_copiesEveryTemplateForTradeIntoUserLibrary() {
        User electrician = User.builder()
                .id(UUID.randomUUID())
                .trades(new LinkedHashSet<>(Set.of(Trade.ELECTRICAL)))
                .build();
        given(templateRepository.findByTradeIn(electrician.getTrades())).willReturn(List.of(
                tpl("Розетки та вимикачі", "Розетка", ItemType.WORK, Unit.PIECE, "180.00"),
                tpl("Кабельні роботи", "Кабель", ItemType.MATERIAL, Unit.M, "38.50")
        ));
        given(catalogRepository.findByOwnerIdOrderByNameAsc(electrician.getId())).willReturn(List.of());

        int added = catalogTemplateService.seedForUser(electrician);

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<List<CatalogItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(CatalogItem::getName)
                .containsExactlyInAnyOrder("Розетка", "Кабель");
        // Category is carried over from the template.
        assertThat(captor.getValue())
                .filteredOn(i -> i.getName().equals("Розетка"))
                .extracting(CatalogItem::getCategory)
                .containsExactly("Розетки та вимикачі");
    }

    @Test
    void seedForUser_mergesAllTradesAndDropsCrossTradeDuplicates() {
        User generalist = User.builder()
                .id(UUID.randomUUID())
                .trades(new LinkedHashSet<>(Set.of(Trade.GENERAL, Trade.ELECTRICAL)))
                .build();
        // The merged set contains "Демонтаж" twice (a position shared by two
        // of the user's trades). It must be created only once.
        given(templateRepository.findByTradeIn(generalist.getTrades())).willReturn(List.of(
                tpl("Демонтаж", "Демонтаж стіни", ItemType.WORK, Unit.M2, "280.00"),
                tpl("Розетки та вимикачі", "Розетка", ItemType.WORK, Unit.PIECE, "180.00"),
                tpl("Демонтаж", "Демонтаж стіни", ItemType.WORK, Unit.M2, "280.00")
        ));
        given(catalogRepository.findByOwnerIdOrderByNameAsc(generalist.getId())).willReturn(List.of());

        int added = catalogTemplateService.seedForUser(generalist);

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<List<CatalogItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(CatalogItem::getName)
                .containsExactlyInAnyOrder("Демонтаж стіни", "Розетка");
    }

    @Test
    void resetForUser_skipsItemsThatAlreadyExistByNameTypeUnit() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .trades(new LinkedHashSet<>(Set.of(Trade.TILING)))
                .build();
        given(templateRepository.findByTradeIn(user.getTrades())).willReturn(List.of(
                tpl("Укладка", "Клей для плитки", ItemType.MATERIAL, Unit.PIECE, "280.00"),
                tpl("Укладка", "Хрестики 2мм", ItemType.MATERIAL, Unit.SET, "25.00")
        ));
        // User already has the first template (same name/type/unit) — but
        // crucially with a different price, which we MUST NOT overwrite.
        CatalogItem existing = CatalogItem.builder()
                .name("Клей для плитки")
                .type(ItemType.MATERIAL)
                .unit(Unit.PIECE)
                .defaultPrice(new BigDecimal("999.00"))
                .build();
        given(catalogRepository.findByOwnerIdOrderByNameAsc(user.getId())).willReturn(List.of(existing));

        int added = catalogTemplateService.resetForUser(user);

        assertThat(added).isEqualTo(1);
        ArgumentCaptor<List<CatalogItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(CatalogItem::getName)
                .containsExactly("Хрестики 2мм");
    }

    @Test
    void seedForUser_zeroTemplatesMeansZeroSaves() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .trades(new LinkedHashSet<>(Set.of(Trade.OTHER)))
                .build();
        given(templateRepository.findByTradeIn(user.getTrades())).willReturn(List.of());

        int added = catalogTemplateService.seedForUser(user);

        assertThat(added).isZero();
        verify(catalogRepository, org.mockito.Mockito.never()).saveAll(anyList());
    }

    @Test
    void resetForUser_stampsCurrentUserAsOwnerOnEveryCreatedItem() {
        // Tenant isolation: a reset writes into THIS master's catalog only —
        // every created CatalogItem carries his owner id, never a shared or
        // foreign owner. This is the ownership half of the reset contract.
        User owner = User.builder()
                .id(UUID.randomUUID())
                .trades(new LinkedHashSet<>(Set.of(Trade.ELECTRICAL)))
                .build();
        given(templateRepository.findByTradeIn(owner.getTrades())).willReturn(List.of(
                tpl("Розетки та вимикачі", "Розетка", ItemType.WORK, Unit.PIECE, "180.00"),
                tpl("Кабельні роботи", "Кабель", ItemType.MATERIAL, Unit.M, "38.50")
        ));
        given(catalogRepository.findByOwnerIdOrderByNameAsc(owner.getId())).willReturn(List.of());

        catalogTemplateService.resetForUser(owner);

        ArgumentCaptor<List<CatalogItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.getOwner().getId()).isEqualTo(owner.getId()));
    }

    private CatalogTemplate tpl(String category, String name, ItemType type, Unit unit, String price) {
        return CatalogTemplate.builder()
                .id(UUID.randomUUID())
                .category(category)
                .name(name)
                .type(type)
                .unit(unit)
                .suggestedPrice(new BigDecimal(price))
                .build();
    }
}
