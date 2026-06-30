package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository.TemplateItemCount;
import com.majstr.backend.repository.EstimateTemplateRepository;
import com.majstr.backend.feature.LimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class EstimateTemplateServiceTest {

    @Mock EstimateTemplateRepository templateRepository;
    @Mock EstimateTemplateItemRepository templateItemRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock EstimateItemRepository estimateItemRepository;
    @Mock CatalogItemRepository catalogRepository;
    @Mock ProjectService projectService;
    @Mock LimitService limitService;
    @Mock EstimateService estimateService;
    @InjectMocks EstimateTemplateService service;

    private final UUID ownerId = UUID.randomUUID();

    // ---- apply -------------------------------------------------------------

    @Test
    void applyToProject_substitutesPricesFromOwnCatalogAndLeavesQuantitiesEmpty() {
        UUID templateId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID estimateId = UUID.randomUUID();

        EstimateTemplate template = EstimateTemplate.builder().id(templateId).isDefault(true).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        Project project = Project.builder().id(projectId).build();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project);

        // The master owns ONE of the two positions in their catalog (with their price).
        CatalogItem owned = CatalogItem.builder()
                .name("Укладання плитки керамічної")
                .category("Плитка")
                .type(ItemType.WORK)
                .unit(Unit.M2)
                .defaultPrice(new BigDecimal("350.00"))
                .build();
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of(owned));

        Estimate saved = Estimate.builder().id(estimateId).project(project).build();
        given(estimateRepository.save(any())).willReturn(saved);
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId)).willReturn(List.of(
                templateItem(template, "Укладання плитки керамічної", Unit.M2, 0),
                templateItem(template, "Затирка швів проста", Unit.M2, 1) // NOT in catalog
        ));
        EstimateResponse stub = stubResponse(estimateId, projectId);
        given(estimateService.get(estimateId, ownerId)).willReturn(stub);

        EstimateResponse result = service.applyToProject(
                projectId, templateId, new EstimateCreateRequest(null, null, "Кухня"), ownerId);

        assertThat(result).isSameAs(stub);
        ArgumentCaptor<List<EstimateItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(estimateItemRepository).saveAll(captor.capture());
        List<EstimateItem> items = captor.getValue();
        assertThat(items).hasSize(2);
        // every applied item starts with an empty (zero) quantity
        assertThat(items).allSatisfy(i -> assertThat(i.getQuantity()).isEqualByComparingTo("0"));

        EstimateItem matched = items.get(0);
        assertThat(matched.getName()).isEqualTo("Укладання плитки керамічної");
        assertThat(matched.getUnitPrice()).isEqualByComparingTo("350.00"); // from catalog
        assertThat(matched.getCategory()).isEqualTo("Плитка");

        EstimateItem unmatched = items.get(1);
        assertThat(unmatched.getName()).isEqualTo("Затирка швів проста");
        assertThat(unmatched.getUnitPrice()).isEqualByComparingTo("0"); // no catalog match → empty price
        assertThat(unmatched.getCategory()).isNull();
        assertThat(unmatched.getUnit()).isEqualTo(Unit.M2); // falls back to template unit

        verify(limitService).requireCanAddEstimate(ownerId, projectId);
    }

    @Test
    void applyToProject_rejectsAnotherMastersOwnTemplate() {
        UUID templateId = UUID.randomUUID();
        User otherOwner = User.builder().id(UUID.randomUUID()).build();
        EstimateTemplate foreign = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(otherOwner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.applyToProject(
                UUID.randomUUID(), templateId, new EstimateCreateRequest(null, null, null), ownerId))
                .isInstanceOf(AccessDeniedException.class);
        verify(estimateRepository, never()).save(any());
    }

    // ---- save as template --------------------------------------------------

    @Test
    void saveFromEstimate_keepsNamesAndUnitsButDropsQuantitiesAndPrices() {
        UUID estimateId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        Estimate estimate = Estimate.builder().id(estimateId).project(project).build();
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of(
                estimateItem("Укладання плитки", ItemType.WORK, Unit.M2, new BigDecimal("4.5"), new BigDecimal("350"), 0),
                estimateItem("Клей", ItemType.MATERIAL, Unit.PIECE, new BigDecimal("3"), new BigDecimal("280"), 1)
        ));
        given(templateRepository.save(any())).willAnswer(inv -> {
            EstimateTemplate t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        EstimateTemplateSummary summary = service.saveFromEstimate(estimateId, "  Санвузол Іванова  ", ownerId);

        ArgumentCaptor<EstimateTemplate> tplCaptor = ArgumentCaptor.forClass(EstimateTemplate.class);
        verify(templateRepository).save(tplCaptor.capture());
        EstimateTemplate tpl = tplCaptor.getValue();
        assertThat(tpl.getName()).isEqualTo("Санвузол Іванова"); // trimmed
        assertThat(tpl.isDefault()).isFalse();
        assertThat(tpl.getOwner()).isSameAs(owner);
        assertThat(tpl.getTrade()).isNull(); // own templates are trade-less

        ArgumentCaptor<List<EstimateTemplateItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(templateItemRepository).saveAll(itemsCaptor.capture());
        List<EstimateTemplateItem> items = itemsCaptor.getValue();
        assertThat(items).extracting(EstimateTemplateItem::getName)
                .containsExactly("Укладання плитки", "Клей");
        assertThat(items).extracting(EstimateTemplateItem::getUnit)
                .containsExactly(Unit.M2, Unit.PIECE);
        assertThat(items).extracting(EstimateTemplateItem::getType)
                .containsExactly(ItemType.WORK, ItemType.MATERIAL);
        assertThat(summary.itemCount()).isEqualTo(2);
        assertThat(summary.isDefault()).isFalse();
    }

    // ---- listing -----------------------------------------------------------

    @Test
    void listForUser_returnsDefaultsAndOwnWithItemCountsFolded() {
        User user = User.builder()
                .id(ownerId)
                .trades(new LinkedHashSet<>(Set.of(Trade.TILING)))
                .build();
        EstimateTemplate def = EstimateTemplate.builder()
                .id(UUID.randomUUID()).name("Санвузол повний").trade(Trade.TILING).isDefault(true).build();
        EstimateTemplate mine = EstimateTemplate.builder()
                .id(UUID.randomUUID()).name("Моя ванна").isDefault(false).owner(user).build();
        given(templateRepository.findDefaultsForTrades(user.getTrades())).willReturn(List.of(def));
        given(templateRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)).willReturn(List.of(mine));
        given(templateItemRepository.countByTemplateIds(anyList())).willReturn(List.of(
                count(def.getId(), 8), count(mine.getId(), 3)));

        List<EstimateTemplateSummary> list = service.listForUser(user);

        assertThat(list).extracting(EstimateTemplateSummary::name)
                .containsExactly("Санвузол повний", "Моя ванна");
        assertThat(list).extracting(EstimateTemplateSummary::itemCount)
                .containsExactly(8, 3);
        assertThat(list).extracting(EstimateTemplateSummary::isDefault)
                .containsExactly(true, false);
    }

    // ---- access on read / preview -----------------------------------------

    @Test
    void get_allowsADefaultTemplate() {
        UUID id = UUID.randomUUID();
        EstimateTemplate def = EstimateTemplate.builder().id(id).name("Санвузол").isDefault(true).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(def));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id)).willReturn(List.of(
                templateItem(def, "Грунтовка", Unit.M2, 0)));

        EstimateTemplateDetail detail = service.get(id, ownerId);

        assertThat(detail.name()).isEqualTo("Санвузол");
        assertThat(detail.items()).hasSize(1);
    }

    @Test
    void delete_rejectsASystemDefault() {
        UUID id = UUID.randomUUID();
        EstimateTemplate def = EstimateTemplate.builder().id(id).isDefault(true).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(def));

        assertThatThrownBy(() -> service.delete(id, ownerId)).isInstanceOf(AccessDeniedException.class);
        verify(templateRepository, never()).delete(any());
    }

    // ---- edit own template items -------------------------------------------

    @Test
    void addItem_appendsToOwnTemplateWithNextSortOrder() {
        UUID templateId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(List.of(templateItem(template, "Існуюча", Unit.M2, 0)));
        given(templateItemRepository.save(any())).willAnswer(inv -> {
            EstimateTemplateItem i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        var detail = service.addItem(
                templateId, new TemplateItemRequest("Нова позиція", ItemType.WORK, Unit.PIECE), ownerId);

        ArgumentCaptor<EstimateTemplateItem> captor = ArgumentCaptor.forClass(EstimateTemplateItem.class);
        verify(templateItemRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Нова позиція");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(1); // appended after sortOrder 0
        assertThat(detail.items()).extracting(d -> d.name())
                .containsExactly("Існуюча", "Нова позиція");
    }

    @Test
    void addItem_rejectsASystemDefault() {
        UUID templateId = UUID.randomUUID();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(
                EstimateTemplate.builder().id(templateId).isDefault(true).build()));

        assertThatThrownBy(() -> service.addItem(
                templateId, new TemplateItemRequest("x", ItemType.WORK, Unit.M2), ownerId))
                .isInstanceOf(AccessDeniedException.class);
        verify(templateItemRepository, never()).save(any());
    }

    @Test
    void removeItem_deletesFromOwnTemplate() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        EstimateTemplateItem item = templateItem(template, "Видалити", Unit.M2, 0);
        given(templateItemRepository.findById(itemId)).willReturn(Optional.of(item));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(List.of());

        service.removeItem(templateId, itemId, ownerId);

        verify(templateItemRepository).delete(item);
    }

    @Test
    void removeItem_rejectsASystemDefault() {
        UUID templateId = UUID.randomUUID();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(
                EstimateTemplate.builder().id(templateId).isDefault(true).build()));

        assertThatThrownBy(() -> service.removeItem(templateId, UUID.randomUUID(), ownerId))
                .isInstanceOf(AccessDeniedException.class);
        verify(templateItemRepository, never()).delete(any());
    }

    // ---- helpers -----------------------------------------------------------

    private EstimateTemplateItem templateItem(EstimateTemplate t, String name, Unit unit, int sort) {
        return EstimateTemplateItem.builder()
                .id(UUID.randomUUID()).template(t).name(name).type(ItemType.WORK).unit(unit).sortOrder(sort).build();
    }

    private EstimateItem estimateItem(String name, ItemType type, Unit unit,
                                      BigDecimal qty, BigDecimal price, int sort) {
        return EstimateItem.builder()
                .id(UUID.randomUUID()).name(name).type(type).unit(unit)
                .quantity(qty).unitPrice(price).sortOrder(sort).build();
    }

    private static TemplateItemCount count(UUID id, long n) {
        return new TemplateItemCount() {
            public UUID getTemplateId() { return id; }
            public long getCnt() { return n; }
        };
    }

    private static EstimateResponse stubResponse(UUID estimateId, UUID projectId) {
        return new EstimateResponse(estimateId, projectId, "Кухня",
                com.majstr.backend.entity.EstimateStatus.DRAFT, null, null, null, null,
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
