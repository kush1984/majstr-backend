package com.majstr.backend.service;

import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class EstimatePdfServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private StorageService storage;
    @InjectMocks private EstimatePdfService pdfService;

    private final PdfFontProvider fonts = new PdfFontProvider();

    @BeforeEach
    void wireFonts() throws Exception {
        fonts.init(); // loads DejaVu Sans from classpath
        var field = EstimatePdfService.class.getDeclaredField("fonts");
        field.setAccessible(true);
        field.set(pdfService, fonts);
    }

    @Test
    void render_producesNonEmptyPdfWithUkrainianContent() throws Exception {
        given(featureGuard.isEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Feature.BRANDED_PDF)))
                .willReturn(false); // no logo path exercised; tests the Cyrillic-text path

        EstimatePdfService.PdfModel model = sampleModel();

        byte[] pdf = pdfService.render(model);

        assertThat(pdf).isNotEmpty();
        assertThat(pdf.length).isGreaterThan(1024);
        // PDF magic
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void render_datesTheEstimateInKyivTime_notUTC() throws Exception {
        // 2026-03-10T23:30Z is already 01:30 on the 11th in Kyiv. Formatting the instant in
        // UTC — as this did — printed «10.03.2026» on the document the client keeps and
        // signs. Any estimate finished late in the evening was dated to the day before.
        given(featureGuard.isEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Feature.BRANDED_PDF)))
                .willReturn(false);
        EstimatePdfService.PdfModel model = sampleModel(Instant.parse("2026-03-10T23:30:00Z"));

        byte[] pdf = pdfService.render(model);

        String text;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("11.03.2026");
        assertThat(text).doesNotContain("10.03.2026");
    }

    private EstimatePdfService.PdfModel sampleModel() {
        return sampleModel(Instant.now());
    }

    private EstimatePdfService.PdfModel sampleModel(Instant createdAt) {
        User contractor = User.builder()
                .id(UUID.randomUUID())
                .email("ivan@example.com")
                .companyName("Іван-Електрик ФОП")
                .fullName("Іван Майстренко")
                .phone("+380501112233")
                .trades(Set.of(Trade.ELECTRICAL))
                .passwordHash("x")
                .build();
        Client client = Client.builder()
                .id(UUID.randomUUID())
                .fullName("Олена Іваненко")
                .phone("+380671234567")
                .build();
        Project project = Project.builder()
                .id(UUID.randomUUID())
                .owner(contractor)
                .client(client)
                .name("Квартира на Хрещатику")
                .address("вул. Хрещатик 1, Київ")
                .status(ProjectStatus.ESTIMATING)
                .build();
        Estimate estimate = Estimate.builder()
                .id(UUID.randomUUID())
                .project(project)
                .status(EstimateStatus.DRAFT)
                .validUntil(LocalDate.of(2026, 6, 30))
                .notes("Передоплата 30%, гарантія 12 місяців")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();

        EstimateItem work = EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(ItemType.WORK)
                .name("Штукатурка стін")
                .unit(Unit.M2)
                .quantity(new BigDecimal("25.500"))
                .unitPrice(new BigDecimal("180.00"))
                .sortOrder(0)
                .build();
        EstimateItem material = EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(ItemType.MATERIAL)
                .name("Гіпсова суміш Knauf")
                .unit(Unit.KG)
                .quantity(new BigDecimal("120.000"))
                .unitPrice(new BigDecimal("18.50"))
                .sortOrder(1)
                .build();

        return new EstimatePdfService.PdfModel(contractor, project, client, estimate, List.of(work, material));
    }

    // ---- sections -------------------------------------------------------------------------------

    @Test
    void render_showsSectionsInTheOrderTheMasterArranged() throws Exception {
        // Sections are not stored anywhere: a section IS the run of lines sharing a category, and
        // the order follows sortOrder. So this asserts the one thing that can break — that the PDF
        // preserves the arrangement instead of re-sorting by name or by category alphabetically.
        given(featureGuard.isEnabled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sectionedModel()));

        assertThat(text).contains("Плитка").contains("Підготовка");
        // «Плитка» is dragged ABOVE «Підготовка» (sortOrder 0,1 vs 2), and alphabetical order would
        // put Підготовка first — so index order is what proves the arrangement survived.
        assertThat(text.indexOf("Плитка")).isLessThan(text.indexOf("Підготовка"));

        // A subtotal per section: 20 × 850 = 17 000 for Плитка, 10 × 40 = 400 for Підготовка.
        assertThat(digitsOnly(text)).contains("1700000").contains("40000");
        assertThat(text).contains("Разом по розділу");
    }

    @Test
    void render_leavesAnEstimateWithoutCategoriesExactlyAsItWas() throws Exception {
        // A master who never files their lines must not be shown a «Без категорії» heading over the
        // whole document, nor a subtotal that just repeats the grand total.
        given(featureGuard.isEnabled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Feature.BRANDED_PDF))).willReturn(false);

        String text = textOf(pdfService.render(sampleModel()));

        assertThat(text).doesNotContain("Без категорії").doesNotContain("Разом по розділу");
        assertThat(text).contains("Штукатурка стін");   // the lines themselves still render
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    /** Money is formatted with locale separators the text extractor may not preserve verbatim. */
    private static String digitsOnly(String text) {
        return text.replaceAll("[^0-9]", "");
    }

    private EstimatePdfService.PdfModel sectionedModel() {
        EstimatePdfService.PdfModel base = sampleModel();
        Estimate estimate = base.estimate();
        List<EstimateItem> items = List.of(
                sectionItem(estimate, ItemType.WORK, "Укладання плитки", "Плитка", "20", "850.00", 0),
                sectionItem(estimate, ItemType.WORK, "Затирка швів", "Плитка", "20", "0.01", 1),
                sectionItem(estimate, ItemType.WORK, "Грунтування", "Підготовка", "10", "40.00", 2),
                sectionItem(estimate, ItemType.MATERIAL, "Клей", "Плитка", "10", "25.00", 3));
        return new EstimatePdfService.PdfModel(
                base.contractor(), base.project(), base.client(), estimate, items);
    }

    private EstimateItem sectionItem(Estimate estimate, ItemType type, String name, String category,
                                     String qty, String price, int sortOrder) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .estimate(estimate)
                .type(type)
                .name(name)
                .category(category)
                .unit(Unit.M2)
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(price))
                .sortOrder(sortOrder)
                .build();
    }

    @Test
    void render_centresTheSectionTitleAndBoldsTheSubtotal() throws Exception {
        // Setting the alignment on the CELL alone did not take: a cell built from a Phrase renders in
        // text mode, where the column layout decides, and the title came out against the right edge.
        // So this measures where the glyphs actually landed rather than trusting the property.
        given(featureGuard.isEnabled(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Feature.BRANDED_PDF))).willReturn(false);

        byte[] pdf = pdfService.render(sectionedModel());
        float titleX = firstXOf(pdf, "Плитка");
        float lineX = firstXOf(pdf, "Укладання плитки");   // left-aligned in the first column
        float subtotalX = firstXOf(pdf, "Разом по розділу");

        // Centred: well to the right of a left-aligned cell, and well to the left of the
        // right-aligned subtotal label. Relative, so it does not depend on page geometry.
        // Measured, not asserted off a property: a left-aligned heading lands at x=45, the same
        // margin as a line, and this used to pass by accident when only the phrase's alignment was
        // changed — it is the cell's that governs.
        assertThat(titleX).as("назва розділу мусить бути по центру, а не по лівому краю").isGreaterThan(lineX + 50);
        assertThat(titleX).isLessThan(subtotalX);
    }

    /** X of the first glyph of `needle`, measured off the rendered page. */
    private static float firstXOf(byte[] pdf, String needle) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            final float[] found = { -1f };
            var stripper = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
                    int at = text.indexOf(needle);
                    if (found[0] < 0 && at >= 0 && at < positions.size()) {
                        found[0] = positions.get(at).getXDirAdj();
                    }
                }
            };
            stripper.getText(doc);
            assertThat(found[0]).as("«" + needle + "» не знайдено на сторінці").isGreaterThanOrEqualTo(0f);
            return found[0];
        }
    }
}
