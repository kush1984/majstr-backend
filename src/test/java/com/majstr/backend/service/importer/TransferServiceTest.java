package com.majstr.backend.service.importer;

import com.majstr.backend.dto.TransferParseResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.importer.EstimateExtractor.Document;
import com.majstr.backend.service.importer.EstimateExtractor.DocumentKind;
import com.majstr.backend.service.importer.EstimateExtractor.Extracted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Reading one file for «Перенести в Majstr»: its kind, its meta, each line filed and checked. */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock FeatureGuard featureGuard;
    @Mock UserRepository userRepository;
    @Mock EstimateExtractor extractor;
    @Mock TradeFiler tradeFiler;
    @Spy DocumentReader documentReader = new DocumentReader();
    @InjectMocks TransferService service;

    private final UUID owner = UUID.randomUUID();

    @Test
    void anEstimateKeepsItsObjectAndFlagsWhatIsMissing() {
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.FREE).build()));
        given(extractor.extractDocument(anyList())).willReturn(new Document(DocumentKind.ESTIMATE,
                "Квартира, Франка 12", "вул. Франка 12", "Олена", "Кошторис", new Extracted(List.of(
                        new Extracted.Line("Укладання плитки", "м²", new BigDecimal("12"), new BigDecimal("600"), "WORK", "Ванна"),
                        new Extracted.Line("Клей", "кг", null, new BigDecimal("30"), "MATERIAL", "")),
                new BigDecimal("2000"))));
        given(tradeFiler.file(eq(owner), anyList())).willReturn(List.of(
                new TradeFiler.Filing(Trade.TILING, "Підлога"), new TradeFiler.Filing(null, null)));

        TransferParseResponse res = service.parse(owner, "kosht.jpg", "image/jpeg", new byte[] {1});

        verify(featureGuard).requireFeature(any(User.class), eq(Feature.ESTIMATE_IMPORT));
        assertThat(res.kind()).isEqualTo(DocumentKind.ESTIMATE);
        assertThat(res.objectName()).isEqualTo("Квартира, Франка 12");
        assertThat(res.depositAmount()).isEqualByComparingTo("2000");
        assertThat(res.items().get(0)).satisfies(l -> {
            assertThat(l.unit()).isEqualTo(Unit.M2);
            assertThat(l.trade()).isEqualTo(Trade.TILING);
            assertThat(l.section()).isEqualTo("Ванна");
            assertThat(l.issues()).isEmpty();
        });
        assertThat(res.items().get(1)).satisfies(l -> {
            assertThat(l.type()).isEqualTo(ItemType.MATERIAL);
            assertThat(l.trade()).isNull();
            assertThat(l.section()).isNull();
            assertThat(l.issues()).containsExactly("quantity");
        });
    }

    @Test
    void aPriceListHasNoQuantitiesAndNoDeposit() {
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.FREE).build()));
        given(extractor.extractDocument(anyList())).willReturn(new Document(DocumentKind.PRICE_LIST,
                null, null, null, "Прайс", new Extracted(List.of(
                        new Extracted.Line("Штукатурка стін", "м2", new BigDecimal("3"), new BigDecimal("250"), "WORK", null)),
                new BigDecimal("100"))));
        given(tradeFiler.file(eq(owner), anyList())).willReturn(List.of(new TradeFiler.Filing(null, null)));

        TransferParseResponse res = service.parse(owner, "prais.jpg", "image/jpeg", new byte[] {1});

        assertThat(res.kind()).isEqualTo(DocumentKind.PRICE_LIST);
        assertThat(res.depositAmount()).isNull();
        assertThat(res.items().getFirst().quantity()).isEqualByComparingTo("0");
        assertThat(res.items().getFirst().issues()).isEmpty();
    }
}
