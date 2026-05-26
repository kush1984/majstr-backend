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

    private EstimatePdfService.PdfModel sampleModel() {
        User contractor = User.builder()
                .id(UUID.randomUUID())
                .email("ivan@example.com")
                .companyName("Іван-Електрик ФОП")
                .fullName("Іван Майстренко")
                .phone("+380501112233")
                .trade(Trade.ELECTRICAL)
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
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
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
