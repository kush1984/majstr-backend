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
import java.util.List;
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
                .trade(Trade.ELECTRICAL)
                .build();
        given(templateRepository.findByTrade(Trade.ELECTRICAL)).willReturn(List.of(
                tpl("Розетка", ItemType.WORK, Unit.PIECE, "180.00"),
                tpl("Кабель", ItemType.MATERIAL, Unit.M, "38.50")
        ));
        given(catalogRepository.findByOwnerIdOrderByNameAsc(electrician.getId())).willReturn(List.of());

        int added = catalogTemplateService.seedForUser(electrician);

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<List<CatalogItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(catalogRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(CatalogItem::getName)
                .containsExactlyInAnyOrder("Розетка", "Кабель");
    }

    @Test
    void resetForUser_skipsItemsThatAlreadyExistByNameTypeUnit() {
        User user = User.builder().id(UUID.randomUUID()).trade(Trade.TILING).build();
        given(templateRepository.findByTrade(Trade.TILING)).willReturn(List.of(
                tpl("Клей для плитки", ItemType.MATERIAL, Unit.PIECE, "280.00"),
                tpl("Хрестики 2мм", ItemType.MATERIAL, Unit.SET, "25.00")
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
        User user = User.builder().id(UUID.randomUUID()).trade(Trade.OTHER).build();
        given(templateRepository.findByTrade(Trade.OTHER)).willReturn(List.of());

        int added = catalogTemplateService.seedForUser(user);

        assertThat(added).isZero();
        verify(catalogRepository, org.mockito.Mockito.never()).saveAll(anyList());
    }

    private CatalogTemplate tpl(String name, ItemType type, Unit unit, String price) {
        return CatalogTemplate.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .unit(unit)
                .suggestedPrice(new BigDecimal(price))
                .build();
    }
}
