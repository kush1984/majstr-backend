package com.majstr.backend.service;

import com.majstr.backend.dto.AdminEstimateTemplateRequest;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateTemplateItemRepository;
import com.majstr.backend.repository.EstimateTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class AdminEstimateTemplateServiceTest {

    @Mock EstimateTemplateRepository templateRepository;
    @Mock EstimateTemplateItemRepository itemRepository;
    @InjectMocks AdminEstimateTemplateService service;

    private EstimateTemplate defaultTemplate(UUID id) {
        return EstimateTemplate.builder().id(id).name("Санвузол").trade(Trade.TILING).isDefault(true).build();
    }

    @Test
    void create_makesDefaultOwnerlessTemplate() {
        given(templateRepository.save(any(EstimateTemplate.class))).willAnswer(inv -> inv.getArgument(0));

        EstimateTemplateSummary summary = service.create(
                new AdminEstimateTemplateRequest("  Новий шаблон ", Trade.PLUMBING), "admin@majstr");

        ArgumentCaptor<EstimateTemplate> captor = ArgumentCaptor.forClass(EstimateTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().isDefault()).isTrue();
        assertThat(captor.getValue().getOwner()).isNull();
        assertThat(summary.name()).isEqualTo("Новий шаблон");
        assertThat(summary.isDefault()).isTrue();
        assertThat(summary.itemCount()).isZero();
    }

    @Test
    void get_rejectsNonDefaultTemplate() {
        UUID id = UUID.randomUUID();
        EstimateTemplate own = EstimateTemplate.builder().id(id).name("Мій").isDefault(false).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(own));

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_rejectsNonDefaultTemplate() {
        UUID id = UUID.randomUUID();
        EstimateTemplate own = EstimateTemplate.builder().id(id).name("Мій").isDefault(false).build();
        given(templateRepository.findById(id)).willReturn(Optional.of(own));

        assertThatThrownBy(() ->
                service.update(id, new AdminEstimateTemplateRequest("x", null), "admin@majstr"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addItem_appendsWithNextSortOrder() {
        UUID id = UUID.randomUUID();
        EstimateTemplate t = defaultTemplate(id);
        given(templateRepository.findById(id)).willReturn(Optional.of(t));
        given(itemRepository.findByTemplateIdOrderBySortOrderAscIdAsc(id)).willReturn(List.of(
                EstimateTemplateItem.builder().id(UUID.randomUUID()).template(t).name("A").type(ItemType.WORK).unit(Unit.M2).sortOrder(0).build(),
                EstimateTemplateItem.builder().id(UUID.randomUUID()).template(t).name("B").type(ItemType.WORK).unit(Unit.M2).sortOrder(1).build()
        ));
        given(itemRepository.save(any(EstimateTemplateItem.class))).willAnswer(inv -> inv.getArgument(0));

        EstimateTemplateDetail detail = service.addItem(
                id, new TemplateItemRequest("Затирка швів", ItemType.WORK, Unit.M2), "admin@majstr");

        ArgumentCaptor<EstimateTemplateItem> captor = ArgumentCaptor.forClass(EstimateTemplateItem.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(2); // appended last
        assertThat(captor.getValue().getName()).isEqualTo("Затирка швів");
        assertThat(detail.items()).hasSize(3);
    }

    @Test
    void removeItem_rejectsItemBelongingToAnotherTemplate() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        given(templateRepository.findById(id)).willReturn(Optional.of(defaultTemplate(id)));
        EstimateTemplate other = defaultTemplate(UUID.randomUUID());
        EstimateTemplateItem foreign = EstimateTemplateItem.builder()
                .id(itemId).template(other).name("X").type(ItemType.WORK).unit(Unit.M2).sortOrder(0).build();
        given(itemRepository.findById(itemId)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.removeItem(id, itemId, "admin@majstr"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(itemRepository, never()).delete(any());
    }

    @Test
    void delete_removesDefaultTemplate() {
        UUID id = UUID.randomUUID();
        EstimateTemplate t = defaultTemplate(id);
        given(templateRepository.findById(id)).willReturn(Optional.of(t));

        service.delete(id, "admin@majstr");

        verify(templateRepository).delete(t);
    }
}
