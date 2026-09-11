package com.majstr.backend.service;

import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.ShoppingListItemSource;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingListPdfServiceTest {

    private ShoppingListPdfService pdfService;

    @BeforeEach
    void setUp() throws Exception {
        PdfFontProvider fonts = new PdfFontProvider();
        fonts.init();
        pdfService = new ShoppingListPdfService(fonts);
    }

    @Test
    void render_splitsWhatIsLeftFromWhatIsAlreadyBought() throws Exception {
        String text = textOf(pdfService.render(model(list(
                row("Профіль CD 60/27", Unit.M, "48", false, null),
                row("Шпаклівка фінішна", Unit.KG, "25", true, null)), false)));

        assertThat(text).contains("СПИСОК МАТЕРІАЛІВ");
        assertThat(text).contains("Обʼєкт: Квартира на Липках");
        assertThat(text).contains("Майстер: ФОП Іваненко").contains("+380501112233");
        // Both sections, because a client's usual question is «що вже куплено», and a sheet that
        // silently drops the settled half looks shorter than the job is.
        assertThat(text).contains("ТРЕБА КУПИТИ").contains("Профіль CD 60/27");
        assertThat(text).contains("УЖЕ КУПЛЕНО").contains("Шпаклівка фінішна");
        assertThat(text).contains("48").contains("25");
    }

    @Test
    void render_carriesNoPriceAtAll() throws Exception {
        String text = textOf(pdfService.render(model(list(
                row("Профіль CD 60/27", Unit.M, "48", false, null)), false)));

        // The V126/V81 rule, on the one surface where breaking it would read as a quote.
        assertThat(text).doesNotContain("₴");
        assertThat(text).doesNotContain("Сума").doesNotContain("Разом");
        assertThat(text).contains("Ціни не вказано");
    }

    @Test
    void render_keepsTheRowNoteBesideTheName() throws Exception {
        String text = textOf(pdfService.render(model(list(
                row("Профіль CD 60/27", Unit.M, "48", false, "той самий, що на кухні")), false)));

        assertThat(text).contains("той самий, що на кухні");
    }

    @Test
    void render_withAnUnsignedSource_warnsThatQuantitiesCanMove() throws Exception {
        String signed = textOf(pdfService.render(model(list(
                row("Профіль CD 60/27", Unit.M, "48", false, null)), false)));
        String unsigned = textOf(pdfService.render(model(list(
                row("Профіль CD 60/27", Unit.M, "48", false, null)), true)));

        assertThat(signed).doesNotContain("ще не підписано");
        assertThat(unsigned).contains("ще не підписано");
    }

    @Test
    void render_emptyList_saysSoInsteadOfPrintingBareHeadings() throws Exception {
        String text = textOf(pdfService.render(model(list(), false)));

        assertThat(text).contains("Список порожній");
        assertThat(text).doesNotContain("ТРЕБА КУПИТИ");
        assertThat(text).doesNotContain("Ціни не вказано");
    }

    // ---- fixtures ---------------------------------------------------------

    private ShoppingListPdfService.PdfModel model(ShoppingListResponse list, boolean unsignedSource) {
        User owner = User.builder()
                .id(UUID.randomUUID()).email("i@e.com").passwordHash("x")
                .fullName("Іван Іваненко").companyName("ФОП Іваненко").phone("+380501112233")
                .build();
        Project project = Project.builder()
                .id(UUID.randomUUID()).owner(owner)
                .name("Квартира на Липках").address("вул. Інститутська 5, Київ")
                .status(ProjectStatus.IN_PROGRESS)
                .build();
        ShoppingListResponse withFlag = new ShoppingListResponse(
                list.id(), project.getId(), project.getName(), null,
                list.totalCount(), list.boughtCount(), unsignedSource, list.items());
        return new ShoppingListPdfService.PdfModel(owner, project, withFlag);
    }

    private ShoppingListResponse list(ShoppingListItemResponse... items) {
        List<ShoppingListItemResponse> rows = List.of(items);
        int bought = (int) rows.stream().filter(ShoppingListItemResponse::bought).count();
        return new ShoppingListResponse(UUID.randomUUID(), UUID.randomUUID(), "Квартира на Липках",
                null, rows.size(), bought, false, rows);
    }

    private ShoppingListItemResponse row(String name, Unit unit, String quantity,
                                         boolean bought, String note) {
        return new ShoppingListItemResponse(UUID.randomUUID(), null, name, unit,
                new BigDecimal(quantity), bought, null, false, null, false,
                ShoppingListItemSource.CALCULATOR, null, note, 0);
    }

    private static String textOf(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }
}
