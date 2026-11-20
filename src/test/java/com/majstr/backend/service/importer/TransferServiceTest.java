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
    @Spy CatalogImportParser tableParser = new CatalogImportParser();
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

    // ---- a TABLE is read without the AI (one «Перенести» door, 2026-11-20) -----------------------

    private static final byte[] PRICE_CSV = ("Найменування;Од.;Ціна\n"
            + "Штукатурка стін;м2;250\n"
            + "Монтаж розетки;шт;180\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void aPriceListTableIsReadWithoutTheAi_onAnyPlan_withItsColumns() {
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.FREE).build()));
        given(tradeFiler.file(eq(owner), anyList())).willReturn(List.of(
                new TradeFiler.Filing(Trade.PAINTER, "Штукатурка"), new TradeFiler.Filing(Trade.ELECTRICAL, null)));

        TransferParseResponse res = service.parse(owner, "prais.csv", "text/csv", PRICE_CSV);

        org.mockito.Mockito.verifyNoInteractions(extractor);
        verify(featureGuard, org.mockito.Mockito.never()).requireFeature(any(User.class), any());
        assertThat(res.kind()).isEqualTo(DocumentKind.PRICE_LIST);
        assertThat(res.items()).extracting(TransferParseResponse.Line::name)
                .containsExactly("Штукатурка стін", "Монтаж розетки");
        assertThat(res.items().getFirst().unitPrice()).isEqualByComparingTo("250");
        assertThat(res.items().getFirst().unit()).isEqualTo(Unit.M2);
        assertThat(res.items().getFirst().trade()).isEqualTo(Trade.PAINTER);
        assertThat(res.table()).isNotNull();
        assertThat(res.table().columns()).hasSize(3).first().asString().contains("Найменування");
        assertThat(res.table().nameCol()).isZero();
        assertThat(res.table().priceCol()).isEqualTo(2);
    }

    @Test
    void theColumnsTheMasterPickedWin() {
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.FREE).build()));
        given(tradeFiler.file(eq(owner), anyList())).willAnswer(inv -> ((List<?>) inv.getArgument(1)).stream()
                .map(p -> new TradeFiler.Filing(null, null)).toList());

        // Name read from the UNIT column, deliberately: what he picked is what is read.
        TransferParseResponse res = service.parse(owner, "prais.csv", "text/csv", PRICE_CSV,
                new com.majstr.backend.dto.CatalogImportParseResponse.Mapping(1, 2, null));

        assertThat(res.table().nameCol()).isEqualTo(1);
        assertThat(res.table().unitCol()).isNull();
        assertThat(res.items()).extracting(TransferParseResponse.Line::name).contains("м2", "шт");
    }

    @Test
    void aTableWithAQuantityColumnIsAnEstimate_andGoesToTheAiWhenAllowed() {
        byte[] estimate = ("Робота;Од.;Кількість;Ціна\nШтукатурка стін;м2;40;250\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.PRO).build()));
        given(featureGuard.isEnabled(any(User.class), eq(Feature.ESTIMATE_IMPORT))).willReturn(true);
        given(extractor.extractDocument(anyList())).willReturn(new Document(DocumentKind.ESTIMATE,
                null, null, null, null, new Extracted(List.of(
                        new Extracted.Line("Штукатурка стін", "м2", new BigDecimal("40"), new BigDecimal("250"), "WORK", null)),
                null)));
        given(tradeFiler.file(eq(owner), anyList())).willReturn(List.of(new TradeFiler.Filing(null, null)));

        TransferParseResponse res = service.parse(owner, "koshtorys.csv", "text/csv", estimate);

        assertThat(res.kind()).isEqualTo(DocumentKind.ESTIMATE);
        assertThat(res.table()).isNull();
    }

    @Test
    void anEstimateTableAMasterMayNotSendToTheAiIsStillReadAsAPriceList() {
        byte[] estimate = ("Робота;Од.;Кількість;Ціна\nШтукатурка стін;м2;40;250\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        given(userRepository.findById(owner)).willReturn(Optional.of(User.builder().id(owner).plan(Plan.FREE).build()));
        given(featureGuard.isEnabled(any(User.class), eq(Feature.ESTIMATE_IMPORT))).willReturn(false);
        given(tradeFiler.file(eq(owner), anyList())).willAnswer(inv -> ((List<?>) inv.getArgument(1)).stream()
                .map(p -> new TradeFiler.Filing(null, null)).toList());

        TransferParseResponse res = service.parse(owner, "koshtorys.csv", "text/csv", estimate);

        org.mockito.Mockito.verifyNoInteractions(extractor);
        assertThat(res.kind()).isEqualTo(DocumentKind.PRICE_LIST);
    }

}
