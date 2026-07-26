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
}
