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
    void commit_dedupsByTradeNameAndType_updatesSkipsAndCreates() {
        UUID owner = UUID.randomUUID();
        CatalogItem demolition = existing("Демонтаж", ItemType.WORK, "100");
        demolition.setTrade(Trade.ELECTRICAL); // the import's trade: same trade + name + type = the same position
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
        assertThat(demolition.isOwnPrice()).as("an imported price is his — it counts in the medians (V225)").isTrue();

        // Exactly one new item saved — with the batch trade, trimmed name, MATERIAL type.
        ArgumentCaptor<CatalogItem> saved = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Фарба біла");
        assertThat(saved.getValue().getType()).isEqualTo(ItemType.MATERIAL);
        assertThat(saved.getValue().getTrade()).isEqualTo(Trade.ELECTRICAL);
        assertThat(saved.getValue().isOwnPrice()).isTrue();
    }

    /** V220: the same name under ANOTHER trade is that trade's own position — a new row, the other untouched. */
    @Test
    void commit_sameNameUnderAnotherTrade_isANewRow() {
        UUID owner = UUID.randomUUID();
        CatalogItem tiling = existing("Прибирання", ItemType.WORK, "40");
        tiling.setTrade(Trade.TILING);
        given(catalogRepository.findByOwnerIdOrderByNameAsc(owner)).willReturn(List.of(tiling));
        given(userRepository.getReferenceById(owner)).willReturn(new User());
        given(catalogRepository.save(org.mockito.ArgumentMatchers.any(CatalogItem.class)))
                .willAnswer(i -> i.getArgument(0));

        CatalogImportCommitResponse res = service().commit(owner, new CatalogImportCommitRequest(
                List.of(new CommitItem("Прибирання", Unit.PIECE, new BigDecimal("55"), ItemType.WORK, null)),
                Trade.ELECTRICAL, DedupPolicy.UPDATE_PRICE));

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.updated()).isZero();
        assertThat(tiling.getDefaultPrice()).isEqualByComparingTo("40");
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

    /**
     * A price list's own sections become folders — and a section he already has under that trade is
     * that folder, whatever the spelling, never a twin beside it. A folder of another trade is not his
     * folder here.
     */
    @Test
    void aSectionLandsInTheFolderHeAlreadyHas_spellingBlind_andANewOneIsCreatedOnce() {
        UUID owner = UUID.randomUUID();
        CatalogItem floor = existing("Укладання плитки 60х60", ItemType.WORK, "700");
        floor.setTrade(Trade.TILING);
        floor.setCategory("Підлога");
        CatalogItem paint = existing("Шпаклювання", ItemType.WORK, "150");
        paint.setTrade(Trade.PAINTER);
        paint.setCategory("Підготовка");
        given(catalogRepository.findByOwnerIdOrderByNameAsc(owner)).willReturn(List.of(floor, paint));
        given(userRepository.getReferenceById(owner)).willReturn(new User());
        given(catalogRepository.save(org.mockito.ArgumentMatchers.any(CatalogItem.class)))
                .willAnswer(i -> i.getArgument(0));

        service().commitFiled(owner, List.of(
                new CatalogImportService.FiledItem("Затирка швів", Unit.M2, new BigDecimal("280"), ItemType.WORK,
                        Trade.TILING, "  ПІДЛОГА ", null),
                new CatalogImportService.FiledItem("Демонтаж плитки", Unit.M2, new BigDecimal("180"), ItemType.WORK,
                        Trade.TILING, "Підготовка", null),
                new CatalogImportService.FiledItem("Ґрунтування", Unit.M2, new BigDecimal("25"), ItemType.WORK,
                        Trade.TILING, "підготовка", null)), DedupPolicy.SKIP);

        ArgumentCaptor<CatalogItem> saved = ArgumentCaptor.forClass(CatalogItem.class);
        verify(catalogRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(CatalogItem::getCategory)
                .as("his «Підлога»; a NEW tiling «Підготовка» (the painter one is another trade's), named once")
                .containsExactly("Підлога", "Підготовка", "Підготовка");
    }

}
