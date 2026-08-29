package com.majstr.backend.service;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ClientType;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WorkActPdfServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private StorageService storage;
    @InjectMocks private WorkActPdfService pdfService;

    private final PdfFontProvider fonts = new PdfFontProvider();

    @BeforeEach
    void wireFonts() throws Exception {
        fonts.init();
        var field = WorkActPdfService.class.getDeclaredField("fonts");
        field.setAccessible(true);
        field.set(pdfService, fonts);
    }

    @Test
    void render_interimActWithAdditionalWorks_hasAllTheMandatoryBlocks() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sampleModel(WorkActKind.INTERIM)));

        assertThat(text).contains("АКТ № 7 приймання-передачі виконаних робіт");
        assertThat(text).contains("Штукатурні роботи"); // the stage name right under the heading
        // With a stage name the «(проміжний)» LABEL is dropped (master feedback) — but the legal
        // interim DISCLAIMER below still renders: it hangs on the kind, not the label.
        assertThat(text).doesNotContain("(проміжний)");
        // Two DISTINCT dates.
        assertThat(text).contains("Дата складання");
        assertThat(text).contains("Роботи виконано в період");
        // Executor + customer requisites.
        assertThat(text).contains("ФОП Іваненко Іван").contains("РНОКПП");
        assertThat(text).contains("ТОВ «Ромашка»").contains("ЄДРПОУ");
        // Additional works section + its agreement clause.
        assertThat(text).contains("ДОДАТКОВІ РОБОТИ").contains("погоджені Замовником");
        // Sum in words of the payable (14 500 + 1 500 = 16 000).
        assertThat(text).contains("прописом").contains("Шістнадцять тисяч гривень");
        // Interim disclaimer.
        assertThat(text).contains("Цей Акт є проміжним");
    }

    @Test
    void render_untitledInterim_keepsTheInterimLabel() throws Exception {
        // No stage name → «(проміжний)» is the only descriptor and must stay (master feedback:
        // the label is dropped only when a custom name replaces it).
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sampleModel(WorkActKind.INTERIM, null, null)));

        assertThat(text).contains("(проміжний)");
    }

    @Test
    void render_finalActOmitsTheInterimDisclaimer() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sampleModel(WorkActKind.FINAL)));

        assertThat(text).doesNotContain("проміжним");
        // The quality statement still renders. Asserting a fragment that can't straddle PDFBox's
        // line-wrap: the full «…не мають» wraps «не\nмають» in extracted text.
        assertThat(text).contains("претензій одна до одної");
    }

    @Test
    void render_withoutCumulativeReference_omitsTheReferenceBlock() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        // cumulative == null (the block is off, first act, or the hashed render) → no block at all,
        // even though the act's showCumulative flag is true.
        String text = textOf(pdfService.render(sampleModel(WorkActKind.INTERIM, null)));

        assertThat(text).doesNotContain("ДОВІДКОВО");
        assertThat(text).doesNotContain("Загалом за кошторисами");
    }

    @Test
    void render_withCumulativeReference_showsThreeMoneyRowsNotAPerLineTable() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        var ref = new WorkActPdfService.CumulativeReference(
                new BigDecimal("74124.00"), new BigDecimal("109791.00"));
        String text = textOf(pdfService.render(sampleModel(WorkActKind.INTERIM, ref)));

        assertThat(text).contains("ДОВІДКОВО");
        // The three object-wide money rows — «Загалом за кошторисами» never existed in the old
        // per-line table, so its presence proves the new shape.
        assertThat(text).contains("Виконано з початку робіт");
        assertThat(text).contains("Загалом за кошторисами");
        assertThat(text).contains("Залишок");
    }

    @Test
    void render_withReceipts_addsTheReceiptsSectionAndBillsThemOnTopOfTheWorks() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        var receipts = List.of(
                new WorkActPdfService.ReceiptRow("Епіцентр, клей + грунтовка",
                        LocalDate.of(2026, 8, 3), new BigDecimal("2400.00"), BigDecimal.ZERO, null, false),
                new WorkActPdfService.ReceiptRow("Нова Пошта, доставка",
                        null, new BigDecimal("600.00"), BigDecimal.ZERO, null, false));
        String text = textOf(pdfService.render(
                sampleModel(WorkActKind.INTERIM, null, "Штукатурні роботи", receipts)));

        assertThat(text).contains("ЧЕКИ ТА РАХУНКИ");
        assertThat(text).contains("Епіцентр, клей + грунтовка").contains("Нова Пошта, доставка");
        // Works 16 000 + receipts 3 000 → the payable in words must be the GRAND total, not the works.
        assertThat(text).contains("Разом за роботами").contains("Разом за чеками");
        assertThat(text).contains("Дев'ятнадцять тисяч гривень");
    }

    @Test
    void render_withoutReceipts_omitsTheSectionEntirely() throws Exception {
        given(featureGuard.isEnabled(any(), eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sampleModel(WorkActKind.INTERIM)));

        assertThat(text).doesNotContain("ЧЕКИ ТА РАХУНКИ");
        assertThat(text).doesNotContain("Разом за чеками");
    }

    private WorkActPdfService.PdfModel sampleModel(WorkActKind kind) {
        return sampleModel(kind, null);
    }

    private WorkActPdfService.PdfModel sampleModel(WorkActKind kind,
                                                   WorkActPdfService.CumulativeReference cumulative) {
        return sampleModel(kind, cumulative, "Штукатурні роботи");
    }

    private WorkActPdfService.PdfModel sampleModel(WorkActKind kind,
                                                   WorkActPdfService.CumulativeReference cumulative,
                                                   String title) {
        return sampleModel(kind, cumulative, title, List.of());
    }

    private WorkActPdfService.PdfModel sampleModel(WorkActKind kind,
                                                   WorkActPdfService.CumulativeReference cumulative,
                                                   String title,
                                                   List<WorkActPdfService.ReceiptRow> receipts) {
        User contractor = User.builder()
                .id(UUID.randomUUID()).email("i@e.com").companyName("ФОП Іваненко").fullName("Іван Іваненко")
                .phone("+380501112233").passwordHash("x")
                .legalName("ФОП Іваненко Іван Іванович").taxId("1234567890")
                .legalAddress("Київ, вул. Хрещатик 1").iban("UA903052992990004149123456789").bankName("ПриватБанк")
                .vatPayer(false).taxGroup((short) 3).taxRate(new BigDecimal("5.00")).docCity("Київ")
                .build();
        Client client = Client.builder()
                .id(UUID.randomUUID()).fullName("Петренко П.П.").phone("+380671234567")
                .clientType(ClientType.COMPANY).legalName("ТОВ «Ромашка»").taxId("12345678")
                .legalAddress("Київ, вул. Промислова 5").signatoryTitle("Директор").signatoryName("Петренко П.П.")
                .build();
        Project project = Project.builder()
                .id(UUID.randomUUID()).owner(contractor).client(client)
                .name("Офіс на Подолі").address("вул. Набережна 10, Київ").status(ProjectStatus.IN_PROGRESS)
                .build();
        UUID estimateId = UUID.randomUUID();
        WorkAct act = WorkAct.builder()
                .id(UUID.randomUUID()).userId(contractor.getId()).project(project).number("7").kind(kind)
                .title(title)
                .status(WorkActStatus.DRAFT).issuedAt(LocalDate.of(2026, 8, 14))
                .periodFrom(LocalDate.of(2026, 8, 1)).periodTo(LocalDate.of(2026, 8, 14))
                .showMaterials(true).showCumulative(true)
                .build();
        WorkActItem main = WorkActItem.builder()
                .workAct(act).estimateItemId(UUID.randomUUID()).estimateId(estimateId).type(ItemType.WORK)
                .name("Шпаклювання стін").unit(Unit.M2).unitPrice(new BigDecimal("145.00"))
                .quantity(new BigDecimal("100.000")).lineTotal(new BigDecimal("14500.00"))
                .cumulativeBefore(new BigDecimal("0.000")).sortOrder(0).build();
        WorkActItem additional = WorkActItem.builder()
                .workAct(act).estimateItemId(null).estimateId(null).type(ItemType.WORK)
                .name("Демонтаж перегородки").unit(Unit.M2).unitPrice(new BigDecimal("500.00"))
                .quantity(new BigDecimal("3.000")).lineTotal(new BigDecimal("1500.00"))
                .cumulativeBefore(new BigDecimal("0.000")).sortOrder(1).build();

        return new WorkActPdfService.PdfModel(contractor, project, client, act,
                List.of(main, additional), receipts, Map.of(estimateId, "Чорнові роботи"), null, cumulative);
    }

    private static String textOf(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }
}
