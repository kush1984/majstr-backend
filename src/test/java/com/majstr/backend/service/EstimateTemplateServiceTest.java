package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.dto.TemplateItemsOrderRequest;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.TemplateDefaultOverride;
import com.majstr.backend.entity.TemplateTradeOverride;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateItemRepository.TemplateItemCount;
import com.majstr.backend.repository.EstimateTemplateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.TemplateDefaultOverrideRepository;
import com.majstr.backend.repository.TemplateTradeOverrideRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.feature.LimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.ArrayList;
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
    @Mock ProjectRepository projectRepository;
    @Mock LimitService limitService;
    @Mock EstimateService estimateService;
    @Mock TemplateTradeOverrideRepository tradeOverrideRepository;
    @Mock TemplateDefaultOverrideRepository defaultOverrideRepository;
    @Mock UserTradeRepository userTradeRepository;
    @Mock UserRepository userRepository;
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
    void applyToProject_fromSeveralTemplates_billsAnOverlappingPositionOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID estimateId = UUID.randomUUID();

        EstimateTemplate bathroom = EstimateTemplate.builder().id(first).isDefault(true).build();
        EstimateTemplate floor = EstimateTemplate.builder().id(second).isDefault(true).build();
        given(templateRepository.findById(first)).willReturn(Optional.of(bathroom));
        given(templateRepository.findById(second)).willReturn(Optional.of(floor));
        Project project = Project.builder().id(projectId).build();
        given(projectService.loadOwned(projectId, ownerId)).willReturn(project);
        given(catalogRepository.findByOwnerIdOrderByNameAsc(ownerId)).willReturn(List.of());
        Estimate saved = Estimate.builder().id(estimateId).project(project).build();
        given(estimateRepository.save(any())).willReturn(saved);

        // Both bundles carry the primer — every tiling bundle does. Case differs on purpose.
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(first)).willReturn(List.of(
                templateItem(bathroom, "Ґрунтівка поверхні", Unit.M2, 0),
                templateItem(bathroom, "Укладання плитки 300х600", Unit.M2, 1)));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(second)).willReturn(List.of(
                templateItem(floor, "ҐРУНТІВКА ПОВЕРХНІ", Unit.M2, 0),
                templateItem(floor, "Стяжка маякова цементна", Unit.M2, 1)));
        given(estimateService.get(estimateId, ownerId)).willReturn(stubResponse(estimateId, projectId));

        service.applyToProject(projectId, List.of(first, second),
                new EstimateCreateRequest(null, null, "Санвузол"), ownerId);

        ArgumentCaptor<List<EstimateItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(estimateItemRepository).saveAll(captor.capture());
        List<EstimateItem> items = captor.getValue();

        assertThat(items).extracting(EstimateItem::getName)
                .as("спільна позиція не має потрапити в кошторис двічі — клієнт це побачить")
                .containsExactly("Ґрунтівка поверхні", "Укладання плитки 300х600",
                        "Стяжка маякова цементна");
        assertThat(items).extracting(EstimateItem::getSortOrder)
                .as("порядок наскрізний — інакше шаблони перемішались би між собою")
                .containsExactly(0, 1, 2);
        // One estimate, so one limit check, however many bundles were picked.
        verify(limitService).requireCanAddEstimate(ownerId, projectId);
    }

    @Test
    void applyToProject_fromSeveralTemplates_rejectsIfAnyOfThemIsNotMine() {
        UUID mine = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        given(templateRepository.findById(mine))
                .willReturn(Optional.of(EstimateTemplate.builder().id(mine).isDefault(true).build()));
        given(templateRepository.findById(foreignId)).willReturn(Optional.of(EstimateTemplate.builder()
                .id(foreignId).isDefault(false)
                .owner(User.builder().id(UUID.randomUUID()).build()).build()));

        assertThatThrownBy(() -> service.applyToProject(UUID.randomUUID(), List.of(mine, foreignId),
                new EstimateCreateRequest(null, null, null), ownerId))
                .as("одного чужого шаблону досить, щоб відхилити весь запит")
                .isInstanceOf(AccessDeniedException.class);
        verify(estimateRepository, never()).save(any());
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

        EstimateTemplateSummary summary =
                service.saveFromEstimate(estimateId, "  Санвузол Іванова  ", null, null, ownerId);

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

    @Test
    void saveFromEstimate_withCustomTrade_forcesTradeToOtherAndStoresTheLink() {
        UUID estimateId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        Project project = Project.builder().id(UUID.randomUUID()).owner(owner).build();
        Estimate estimate = Estimate.builder().id(estimateId).project(project).build();
        given(estimateService.loadOwned(estimateId, ownerId)).willReturn(estimate);
        given(estimateItemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(estimateId)).willReturn(List.of());
        com.majstr.backend.entity.UserTrade custom =
                com.majstr.backend.entity.UserTrade.builder().id(UUID.randomUUID()).name("Натяжні стелі").build();
        given(userTradeRepository.findByIdAndUserId(custom.getId(), ownerId)).willReturn(Optional.of(custom));
        given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        EstimateTemplateSummary summary = service.saveFromEstimate(
                estimateId, "Стелі у вітальні", Trade.TILING, custom.getId(), ownerId);

        // A custom trade always wins over any system trade sent alongside it.
        assertThat(summary.trade()).isEqualTo(Trade.OTHER);
        assertThat(summary.customTradeId()).isEqualTo(custom.getId());
        assertThat(summary.customTradeName()).isEqualTo("Натяжні стелі");
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
        given(tradeOverrideRepository.findByUserId(ownerId)).willReturn(List.of());
        given(templateRepository.findDefaultsForTradesOrIds(any(), any())).willReturn(List.of(def));
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

    // ---- re-file into a trade ---------------------------------------------

    @Test
    void setTrade_onOwnTemplate_writesTheTradeOnItsOwnRow_noOverride() {
        UUID id = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate mine = EstimateTemplate.builder()
                .id(id).name("Моя ванна").isDefault(false).owner(owner).trade(null).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(mine));
        given(tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, id)).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id)).willReturn(List.of());

        EstimateTemplateSummary out = service.setTrade(id, Trade.TILING, null, ownerId);

        assertThat(mine.getTrade()).isEqualTo(Trade.TILING);      // stored on the template itself
        assertThat(out.trade()).isEqualTo(Trade.TILING);
        verify(tradeOverrideRepository, never()).save(any());
    }

    @Test
    void setTrade_onOwnTemplate_withCustomTrade_forcesTradeToOtherAndClearsOnNextSystemTradeSwitch() {
        UUID id = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        com.majstr.backend.entity.UserTrade custom =
                com.majstr.backend.entity.UserTrade.builder().id(UUID.randomUUID()).name("Натяжні стелі").build();
        EstimateTemplate mine = EstimateTemplate.builder()
                .id(id).name("Моя стеля").isDefault(false).owner(owner).trade(null).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(mine));
        given(tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, id)).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id)).willReturn(List.of());
        given(userTradeRepository.findByIdAndUserId(custom.getId(), ownerId)).willReturn(Optional.of(custom));

        EstimateTemplateSummary out = service.setTrade(id, Trade.TILING, custom.getId(), ownerId);

        assertThat(mine.getTrade()).isEqualTo(Trade.OTHER);
        assertThat(out.customTradeId()).isEqualTo(custom.getId());

        // Switching back to a plain system trade clears the custom link.
        service.setTrade(id, Trade.TILING, null, ownerId);
        assertThat(mine.getTrade()).isEqualTo(Trade.TILING);
        assertThat(mine.getCustomTrade()).isNull();
    }

    @Test
    void setTrade_onSystemDefault_storesAPerMasterOverride_leavingTheSharedRowUntouched() {
        UUID id = UUID.randomUUID();
        EstimateTemplate def = EstimateTemplate.builder()
                .id(id).name("ГІПСОКАРТОН").isDefault(true).trade(Trade.DRYWALL).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(def));
        given(tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, id)).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id)).willReturn(List.of());

        EstimateTemplateSummary out = service.setTrade(id, Trade.PAINTER, null, ownerId);

        assertThat(def.getTrade()).isEqualTo(Trade.DRYWALL);      // the shared default is NOT mutated
        assertThat(out.trade()).isEqualTo(Trade.PAINTER);
        ArgumentCaptor<TemplateTradeOverride> saved = ArgumentCaptor.forClass(TemplateTradeOverride.class);
        verify(tradeOverrideRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(ownerId);
        assertThat(saved.getValue().getTrade()).isEqualTo(Trade.PAINTER);
    }

    @Test
    void listForUser_appliesTheMastersOwnOverrideOverTheShippedTrade() {
        User user = User.builder().id(ownerId).trades(new LinkedHashSet<>(Set.of(Trade.PAINTER))).build();
        EstimateTemplate def = EstimateTemplate.builder()
                .id(UUID.randomUUID()).name("ГІПСОКАРТОН").trade(Trade.DRYWALL).isDefault(true).build();
        given(tradeOverrideRepository.findByUserId(ownerId)).willReturn(List.of(
                TemplateTradeOverride.builder().userId(ownerId).templateId(def.getId()).trade(Trade.PAINTER).build()));
        given(templateRepository.findDefaultsForTradesOrIds(any(), any())).willReturn(List.of(def));
        given(templateRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)).willReturn(List.of());
        given(templateItemRepository.countByTemplateIds(anyList())).willReturn(List.of(count(def.getId(), 5)));

        List<EstimateTemplateSummary> list = service.listForUser(user);

        // The master moved it to PAINTER — the list must show PAINTER, not the shipped DRYWALL.
        assertThat(list).hasSize(1);
        assertThat(list.get(0).trade()).isEqualTo(Trade.PAINTER);
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
    void delete_onASystemDefault_hidesItForThisMasterOnly() {
        UUID id = UUID.randomUUID();
        EstimateTemplate def = EstimateTemplate.builder().id(id).isDefault(true).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(def));
        given(defaultOverrideRepository.findByUserIdAndTemplateId(ownerId, id)).willReturn(Optional.empty());

        service.delete(id, ownerId);

        // The row is SHARED — really deleting it would empty every other master's picker too.
        verify(templateRepository, never()).delete(any());
        ArgumentCaptor<TemplateDefaultOverride> captor =
                ArgumentCaptor.forClass(TemplateDefaultOverride.class);
        verify(defaultOverrideRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(ownerId);
        assertThat(captor.getValue().getTemplateId()).isEqualTo(id);
        assertThat(captor.getValue().getForkedTemplateId()).as("hidden, not forked").isNull();
    }

    @Test
    void listForUser_dropsADefaultIRetired() {
        UUID hidden = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        User user = User.builder().id(ownerId).trades(new LinkedHashSet<>(Set.of(Trade.PAINTER))).build();
        given(templateRepository.findDefaultsForTradesOrIds(any(), any())).willReturn(List.of(
                EstimateTemplate.builder().id(hidden).name("Фасадні роботи").isDefault(true).build(),
                EstimateTemplate.builder().id(kept).name("Малярні роботи").isDefault(true).build()));
        given(templateRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)).willReturn(List.of());
        given(defaultOverrideRepository.findByUserId(ownerId)).willReturn(List.of(
                TemplateDefaultOverride.builder().userId(ownerId).templateId(hidden).build()));
        given(templateItemRepository.countByTemplateIds(anyList())).willReturn(List.of());

        assertThat(service.listForUser(user)).extracting(EstimateTemplateSummary::name)
                .containsExactly("Малярні роботи");
    }

    @Test
    void restoreDefaults_dropsEveryRetirementRowForTheMaster() {
        service.restoreDefaults(ownerId);
        verify(defaultOverrideRepository).deleteByUserId(ownerId);
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
                .willReturn(new ArrayList<>(List.of(templateItem(template, "Існуюча", Unit.M2, 0))));
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
    void addItem_onASystemDefault_forksItIntoMyOwnEditableCopy() {
        UUID defId = UUID.randomUUID();
        UUID forkId = UUID.randomUUID();
        EstimateTemplate def = EstimateTemplate.builder()
                .id(defId).name("Малярні роботи").trade(Trade.PAINTER).isDefault(true).build();
        given(templateRepository.findById(defId)).willReturn(Optional.of(def));
        given(defaultOverrideRepository.findByUserIdAndTemplateId(ownerId, defId)).willReturn(Optional.empty());
        given(tradeOverrideRepository.findByUserIdAndTemplateId(ownerId, defId)).willReturn(Optional.empty());
        given(userRepository.findById(ownerId)).willReturn(Optional.of(User.builder().id(ownerId).build()));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(defId))
                .willReturn(List.of(templateItem(def, "Грунтування", Unit.M2, 0)));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(forkId))
                .willReturn(new ArrayList<>());
        given(templateRepository.save(any())).willAnswer(inv -> {
            EstimateTemplate t = inv.getArgument(0);
            t.setId(forkId);
            return t;
        });
        given(templateItemRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var detail = service.addItem(
                defId, new TemplateItemRequest("Шліфування", ItemType.WORK, Unit.M2), ownerId);

        ArgumentCaptor<EstimateTemplate> fork = ArgumentCaptor.forClass(EstimateTemplate.class);
        verify(templateRepository).save(fork.capture());
        assertThat(fork.getValue().isDefault()).isFalse();
        assertThat(fork.getValue().getOwner().getId()).isEqualTo(ownerId);
        assertThat(fork.getValue().getName()).isEqualTo("Малярні роботи");
        assertThat(fork.getValue().getTrade()).isEqualTo(Trade.PAINTER);

        // Every position comes across — a fork is the bundle, not an empty shell with one new line.
        ArgumentCaptor<List<EstimateTemplateItem>> copied = ArgumentCaptor.captor();
        verify(templateItemRepository).saveAll(copied.capture());
        assertThat(copied.getValue()).extracting(EstimateTemplateItem::getName)
                .containsExactly("Грунтування");

        // And the shared default steps out of this master's list, pointing at the copy.
        ArgumentCaptor<TemplateDefaultOverride> retired =
                ArgumentCaptor.forClass(TemplateDefaultOverride.class);
        verify(defaultOverrideRepository).save(retired.capture());
        assertThat(retired.getValue().getForkedTemplateId()).isEqualTo(forkId);
        assertThat(detail.id()).as("the client follows the id it gets back").isEqualTo(forkId);
        assertThat(detail.isDefault()).isFalse();
    }

    @Test
    void aSecondWriteOnTheSameDefaultLandsInTheSameFork_notASecondCopy() {
        // Without the recorded fork an offline replay would leave two half-edited bundles.
        UUID defId = UUID.randomUUID();
        UUID forkId = UUID.randomUUID();
        given(templateRepository.findById(defId)).willReturn(Optional.of(
                EstimateTemplate.builder().id(defId).isDefault(true).build()));
        given(templateRepository.findById(forkId)).willReturn(Optional.of(EstimateTemplate.builder()
                .id(forkId).isDefault(false).owner(User.builder().id(ownerId).build()).build()));
        given(defaultOverrideRepository.findByUserIdAndTemplateId(ownerId, defId)).willReturn(
                Optional.of(TemplateDefaultOverride.builder()
                        .userId(ownerId).templateId(defId).forkedTemplateId(forkId).build()));
        given(templateItemRepository.findById(any())).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(forkId)).willReturn(List.of());

        var detail = service.removeItem(defId, UUID.randomUUID(), ownerId);

        assertThat(detail.id()).isEqualTo(forkId);
        verify(templateRepository, never()).save(any());
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
    void updateItem_rewritesNameTypeAndUnitInPlace() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        EstimateTemplateItem item = templateItem(template, "Грунтовка", Unit.M2, 0);
        item.setId(itemId);
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findById(itemId)).willReturn(Optional.of(item));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(List.of(item));

        service.updateItem(templateId, itemId,
                new TemplateItemRequest("  Ґрунтівка глибокого проникнення  ", ItemType.MATERIAL, Unit.PIECE),
                ownerId);

        assertThat(item.getName()).isEqualTo("Ґрунтівка глибокого проникнення"); // trimmed
        assertThat(item.getType()).isEqualTo(ItemType.MATERIAL);
        assertThat(item.getUnit()).isEqualTo(Unit.PIECE);
    }

    @Test
    void updateItem_ofAnAlreadyGonePosition_isANoOp_notA404() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(User.builder().id(ownerId).build()).build()));
        given(templateItemRepository.findById(itemId)).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(List.of());

        var detail = service.updateItem(
                templateId, itemId, new TemplateItemRequest("x", ItemType.WORK, Unit.M2), ownerId);

        assertThat(detail.items()).isEmpty();
    }

    @Test
    void reorderItems_renumbersFromTheListAndKeepsUnmentionedPositionsAfterThem() {
        UUID templateId = UUID.randomUUID();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(User.builder().id(ownerId).build()).build();
        EstimateTemplateItem a = templateItem(template, "Демонтаж", Unit.M2, 0);
        EstimateTemplateItem b = templateItem(template, "Грунтування", Unit.M2, 1);
        EstimateTemplateItem c = templateItem(template, "Фарбування", Unit.M2, 2);
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(new ArrayList<>(List.of(a, b, c)));

        // A duplicate and an unknown id — what a replayed offline drag can actually send.
        service.reorderItems(templateId, new TemplateItemsOrderRequest(
                List.of(c.getId(), a.getId(), c.getId(), UUID.randomUUID())), ownerId);

        assertThat(c.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).as("unmentioned — kept, after the named ones").isEqualTo(2);
    }

    @Test
    void reorderItems_replayedTwice_landsInTheSamePlace() {
        UUID templateId = UUID.randomUUID();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(User.builder().id(ownerId).build()).build();
        EstimateTemplateItem a = templateItem(template, "Демонтаж", Unit.M2, 0);
        EstimateTemplateItem b = templateItem(template, "Фарбування", Unit.M2, 1);
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(new ArrayList<>(List.of(a, b)));
        var req = new TemplateItemsOrderRequest(List.of(b.getId(), a.getId()));

        service.reorderItems(templateId, req, ownerId);
        service.reorderItems(templateId, req, ownerId);

        assertThat(b.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
    }

    @Test
    void reorderItems_rejectsAnotherMastersTemplate() {
        UUID templateId = UUID.randomUUID();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(User.builder().id(UUID.randomUUID()).build()).build()));

        assertThatThrownBy(() -> service.reorderItems(
                templateId, new TemplateItemsOrderRequest(List.of(UUID.randomUUID())), ownerId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- offline authoring: client-provided id (X-Entity-Uuid) --------------

    @Test
    void addItem_withClientId_replayIsIdempotent() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        EstimateTemplateItem already = templateItem(template, "Вже додана", Unit.M2, 0);
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(new ArrayList<>(List.of(already)));
        given(templateItemRepository.findById(itemId)).willReturn(Optional.of(already));

        var detail = service.addItem(
                templateId, new TemplateItemRequest("Вже додана", ItemType.WORK, Unit.M2), ownerId, itemId);

        assertThat(detail.items()).hasSize(1);
        verify(templateItemRepository, never()).save(any());
    }

    @Test
    void addItem_withClientId_rejectsAnIdFromAnotherTemplate() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        EstimateTemplate other = EstimateTemplate.builder()
                .id(UUID.randomUUID()).isDefault(false).owner(owner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(new ArrayList<>());
        given(templateItemRepository.findById(itemId))
                .willReturn(Optional.of(templateItem(other, "Чужа", Unit.M2, 0)));

        assertThatThrownBy(() -> service.addItem(
                templateId, new TemplateItemRequest("x", ItemType.WORK, Unit.M2), ownerId, itemId))
                .isInstanceOf(AccessDeniedException.class);
        verify(templateItemRepository, never()).save(any());
    }

    @Test
    void addItem_withClientId_firstTimeKeepsTheSuppliedId() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        EstimateTemplate template = EstimateTemplate.builder()
                .id(templateId).isDefault(false).owner(owner).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(template));
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(new ArrayList<>());
        given(templateItemRepository.findById(itemId)).willReturn(Optional.empty());
        given(templateItemRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.addItem(templateId, new TemplateItemRequest("Нова", ItemType.WORK, Unit.M2), ownerId, itemId);

        ArgumentCaptor<EstimateTemplateItem> captor = ArgumentCaptor.forClass(EstimateTemplateItem.class);
        verify(templateItemRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(itemId);
    }

    @Test
    void removeItem_alreadyGoneIsANoOp_notA404() {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        given(templateRepository.findById(templateId)).willReturn(Optional.of(
                EstimateTemplate.builder().id(templateId).isDefault(false).owner(owner).build()));
        given(templateItemRepository.findById(itemId)).willReturn(Optional.empty());
        given(templateItemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(templateId))
                .willReturn(List.of());

        service.removeItem(templateId, itemId, ownerId); // replayed offline delete

        verify(templateItemRepository, never()).delete(any());
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        UUID templateId = UUID.randomUUID();
        given(templateRepository.findById(templateId)).willReturn(Optional.empty());

        service.delete(templateId, ownerId);

        verify(templateRepository, never()).delete(any());
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
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, List.of());
    }
}
