package com.majstr.backend.service;

import com.majstr.backend.dto.AdminCatalogTemplatePage;
import com.majstr.backend.dto.AdminCatalogTemplateRequest;
import com.majstr.backend.dto.AdminCatalogTemplateResponse;
import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.CatalogTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminCatalogTemplateServiceTest {

    @Mock CatalogTemplateRepository repository;
    @InjectMocks AdminCatalogTemplateService service;

    private AdminCatalogTemplateRequest req(String name, BigDecimal price) {
        return new AdminCatalogTemplateRequest(Trade.ELECTRICAL, "Щит", name, ItemType.WORK, Unit.PIECE, price);
    }

    @Test
    void create_stampsTheNextVersionSoExistingMastersPickItUp() {
        given(repository.currentVersion()).willReturn(4);
        given(repository.save(any(CatalogTemplate.class))).willAnswer(inv -> inv.getArgument(0));

        AdminCatalogTemplateResponse resp = service.create(req("Новий лічильник", new BigDecimal("600.00")), "admin@majstr");

        // The whole point: a new default is added at currentVersion+1, so every
        // master whose lastSynced < 5 sees it under "Add new from library".
        assertThat(resp.addedInVersion()).isEqualTo(5);
        assertThat(resp.name()).isEqualTo("Новий лічильник");
        assertThat(resp.suggestedPrice()).isEqualByComparingTo("600.00");
    }

    @Test
    void create_trimsNameAndBlankCategoryBecomesNull() {
        given(repository.currentVersion()).willReturn(1);
        given(repository.save(any(CatalogTemplate.class))).willAnswer(inv -> inv.getArgument(0));

        AdminCatalogTemplateResponse resp = service.create(
                new AdminCatalogTemplateRequest(Trade.BUILDER, "  ", "  Моноліт  ", ItemType.WORK, Unit.M3, new BigDecimal("3400.00")),
                "admin@majstr");

        assertThat(resp.name()).isEqualTo("Моноліт");
        assertThat(resp.category()).isNull();
    }

    @Test
    void update_mutatesFieldsButLeavesVersionUntouched() {
        UUID id = UUID.randomUUID();
        CatalogTemplate existing = CatalogTemplate.builder()
                .id(id).trade(Trade.ELECTRICAL).category("Щит").name("Стара назва")
                .type(ItemType.WORK).unit(Unit.PIECE).suggestedPrice(new BigDecimal("100.00"))
                .addedInVersion(2).build();
        given(repository.findById(id)).willReturn(Optional.of(existing));

        AdminCatalogTemplateResponse resp = service.update(
                id, new AdminCatalogTemplateRequest(Trade.PLUMBING, "Каналізація", "Нова назва",
                        ItemType.MATERIAL, Unit.M, new BigDecimal("250.00")), "admin@majstr");

        assertThat(resp.name()).isEqualTo("Нова назва");
        assertThat(resp.trade()).isEqualTo(Trade.PLUMBING);
        assertThat(resp.type()).isEqualTo(ItemType.MATERIAL);
        assertThat(resp.suggestedPrice()).isEqualByComparingTo("250.00");
        // Edits never re-propagate to masters who already copied → version stays.
        assertThat(resp.addedInVersion()).isEqualTo(2);
        verify(repository, never()).save(any());
    }

    @Test
    void update_missingTemplateThrows() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(id, req("x", BigDecimal.ONE), "admin@majstr"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_removesTheTemplate() {
        UUID id = UUID.randomUUID();
        CatalogTemplate existing = CatalogTemplate.builder().id(id).name("Стара").build();
        given(repository.findById(id)).willReturn(Optional.of(existing));

        service.delete(id, "admin@majstr");

        verify(repository).delete(existing);
    }

    @Test
    void list_mapsPageAndReportsCurrentVersion() {
        CatalogTemplate t = CatalogTemplate.builder()
                .id(UUID.randomUUID()).trade(Trade.TILING).category("Укладка").name("Плитка")
                .type(ItemType.WORK).unit(Unit.M2).suggestedPrice(new BigDecimal("600.00"))
                .addedInVersion(1).build();
        given(repository.adminSearch(Trade.TILING, null, null, PageRequest.of(0, 50)))
                .willReturn(new PageImpl<>(List.of(t), PageRequest.of(0, 50), 1));
        given(repository.currentVersion()).willReturn(4);

        AdminCatalogTemplatePage page = service.list(Trade.TILING, null, null, 0, 50);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).name()).isEqualTo("Плитка");
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.currentVersion()).isEqualTo(4);
    }
}
