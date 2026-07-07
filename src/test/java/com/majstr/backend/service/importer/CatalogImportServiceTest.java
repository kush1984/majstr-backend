package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportCommitRequest;
import com.majstr.backend.dto.CatalogImportCommitRequest.CommitItem;
import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CatalogImportServiceTest {

    @Mock CatalogItemRepository catalogRepository;
    @Mock UserRepository userRepository;

    private CatalogImportService service() {
        return new CatalogImportService(catalogRepository, userRepository);
    }

    private CatalogItem existing(String name, ItemType type, String price) {
        return CatalogItem.builder()
                .id(UUID.randomUUID()).name(name).type(type).unit(Unit.PIECE)
                .defaultPrice(new BigDecimal(price)).trade(Trade.OTHER).build();
    }

    @Test
    void commit_dedupsByNameAndType_updatesSkipsAndCreates() {
        UUID owner = UUID.randomUUID();
        CatalogItem demolition = existing("Демонтаж", ItemType.WORK, "100");
        given(catalogRepository.findByOwnerIdOrderByNameAsc(owner)).willReturn(List.of(demolition));
        given(userRepository.getReferenceById(owner)).willReturn(new User());
        given(catalogRepository.save(org.mockito.ArgumentMatchers.any(CatalogItem.class)))
                .willAnswer(i -> i.getArgument(0));

        CatalogImportCommitRequest req = new CatalogImportCommitRequest(
                List.of(
                        // matches "Демонтаж" (case/space-insensitive) → update price
                        new CommitItem("  демонтаж ", Unit.PIECE, new BigDecimal("150"), ItemType.WORK,
                                DedupPolicy.UPDATE_PRICE),
                        // brand-new material → create
                        new CommitItem("Фарба біла", Unit.KG, new BigDecimal("80"), ItemType.MATERIAL, null),
                        // same existing name but SKIP policy → left untouched
                        new CommitItem("Демонтаж", Unit.PIECE, new BigDecimal("999"), ItemType.WORK,
                                DedupPolicy.SKIP)),
                Trade.ELECTRICAL, DedupPolicy.UPDATE_PRICE);

        CatalogImportCommitResponse res = service().commit(owner, req);

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.updated()).isEqualTo(1);
        assertThat(res.skipped()).isEqualTo(1);
        // The existing item's price was updated to the first matching row's value.
        assertThat(demolition.getDefaultPrice()).isEqualByComparingTo("150");

        // Exactly one new item saved — with the batch trade, trimmed name, MATERIAL type.
        ArgumentCaptor<CatalogItem> saved = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Фарба біла");
        assertThat(saved.getValue().getType()).isEqualTo(ItemType.MATERIAL);
        assertThat(saved.getValue().getTrade()).isEqualTo(Trade.ELECTRICAL);
    }

    @Test
    void commit_nullTrade_defaultsToOther() {
        UUID owner = UUID.randomUUID();
        given(catalogRepository.findByOwnerIdOrderByNameAsc(owner)).willReturn(List.of());
        given(userRepository.getReferenceById(owner)).willReturn(new User());
        given(catalogRepository.save(org.mockito.ArgumentMatchers.any(CatalogItem.class)))
                .willAnswer(i -> i.getArgument(0));

        CatalogImportCommitRequest req = new CatalogImportCommitRequest(
                List.of(new CommitItem("Штроба", Unit.LINEAR_METER, new BigDecimal("120"), ItemType.WORK, null)),
                null, DedupPolicy.SKIP);

        service().commit(owner, req);

        ArgumentCaptor<CatalogItem> saved = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogRepository).save(saved.capture());
        assertThat(saved.getValue().getTrade()).isEqualTo(Trade.OTHER);
    }
}
