package com.majstr.backend.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the object's shopping list as a PDF — «поділитись списком з клієнтом» (master's request).
 *
 * <p>The list already travelled as plain text out of the phone's share sheet; a master calls that
 * «дуже примітивно», and he is right: what he hands the client is a document with his name on it.
 * So this is a small, deliberately plain sheet — object, date, the materials, and nothing else.</p>
 *
 * <p><b>No prices, by the same rule the list itself follows (V126/V81).</b> In the shop the price
 * comes off the tag; a forecast printed beside a real number is worse than no number, and a client
 * reading a total here would take it for a quote. The document answers ONE question: what has to be
 * bought, and how much of it.</p>
 *
 * <p>Bought rows are kept, in their own section: the client's usual question is not «що купити» but
 * «що вже куплено», and a list that silently drops the settled half looks shorter than the job is.
 * A tick column carries them, so the same sheet is also printable and usable in the shop.</p>
 */
@Service
@RequiredArgsConstructor
public class ShoppingListPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Color HEADER_BG = new Color(230, 230, 230);

    private final PdfFontProvider fonts;

    /**
     * @param owner   the master — his name is what makes the sheet a document rather than a note
     * @param project the object the list belongs to
     * @param list    the already-rendered list, so the PDF and the screen can never disagree
     */
    public record PdfModel(User owner, Project project, ShoppingListResponse list) {}

    public byte[] render(PdfModel model) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        PdfWriter.getInstance(document, out);
        document.open();
        try {
            addHeader(document, model);
            List<ShoppingListItemResponse> toBuy = model.list().items().stream()
                    .filter(i -> !i.bought())
                    .toList();
            List<ShoppingListItemResponse> bought = model.list().items().stream()
                    .filter(ShoppingListItemResponse::bought)
                    .toList();
            addSection(document, "ТРЕБА КУПИТИ", toBuy, model);
            addSection(document, "УЖЕ КУПЛЕНО", bought, model);
            addFooter(document, model);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    // ---- sections ---------------------------------------------------------

    private void addHeader(Document doc, PdfModel model) throws DocumentException {
        Paragraph title = new Paragraph("СПИСОК МАТЕРІАЛІВ", fonts.bold(14));
        doc.add(title);

        Project project = model.project();
        Paragraph object = new Paragraph("Обʼєкт: " + project.getName(), fonts.regular(10));
        object.setSpacingBefore(8);
        doc.add(object);
        if (notBlank(project.getAddress())) {
            doc.add(new Paragraph("Адреса: " + project.getAddress().trim(), fonts.regular(10)));
        }

        User owner = model.owner();
        String master = notBlank(owner.getCompanyName())
                ? owner.getCompanyName().trim()
                : owner.getFullName();
        if (notBlank(master)) {
            doc.add(new Paragraph("Майстер: " + master.trim(), fonts.regular(10)));
        }
        if (notBlank(owner.getPhone())) {
            doc.add(new Paragraph("Телефон: " + owner.getPhone().trim(), fonts.regular(10)));
        }
        doc.add(new Paragraph("Дата: " + LocalDate.now().format(DATE), fonts.regular(10)));
    }

    private void addSection(Document doc, String title, List<ShoppingListItemResponse> items,
                            PdfModel model) throws DocumentException {
        if (items.isEmpty()) {
            return;
        }
        Paragraph heading = new Paragraph(title, fonts.bold(11));
        heading.setSpacingBefore(16);
        heading.setSpacingAfter(6);
        doc.add(heading);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new int[]{4, 40, 10, 12, 6});
        header(table, "№");
        header(table, "Найменування");
        header(table, "Од.");
        header(table, "К-сть");
        header(table, "✓");

        int n = 1;
        for (ShoppingListItemResponse item : items) {
            cell(table, String.valueOf(n++), Element.ALIGN_CENTER);
            cell(table, itemName(item), Element.ALIGN_LEFT);
            cell(table, UnitLabel.ua(item.unit()), Element.ALIGN_CENTER);
            cell(table, quantity(item.quantity()), Element.ALIGN_RIGHT);
            cell(table, item.bought() ? "✓" : "", Element.ALIGN_CENTER);
        }
        doc.add(table);
    }

    /** The one caveat worth printing: what the client is holding may still move. */
    private void addFooter(Document doc, PdfModel model) throws DocumentException {
        if (model.list().items().isEmpty()) {
            Paragraph empty = new Paragraph("Список порожній.", fonts.regular(10));
            empty.setSpacingBefore(16);
            doc.add(empty);
            return;
        }
        Paragraph note = new Paragraph(
                "Ціни не вказано — вартість матеріалу залежить від магазину на день покупки.",
                fonts.regular(9));
        note.setSpacingBefore(14);
        doc.add(note);
        if (model.list().sourceEstimateUnsigned()) {
            doc.add(new Paragraph(
                    "Кількості пораховано з кошторису, який ще не підписано, — вони можуть змінитися.",
                    fonts.regular(9)));
        }
    }

    // ---- helpers ----------------------------------------------------------

    /** A row's note is the master's own remark («той самий профіль, що на кухні») — it belongs on
     *  the client's copy, so it rides the name rather than being dropped. */
    private String itemName(ShoppingListItemResponse item) {
        return notBlank(item.note())
                ? item.name() + " — " + item.note().trim()
                : item.name();
    }

    /** Quantities are stored scaled; a whole number prints as one, «12» not «12.000». */
    private String quantity(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private void header(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, fonts.bold(9)));
        cell.setBackgroundColor(HEADER_BG);
        cell.setPadding(5);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void cell(PdfPTable table, String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, fonts.regular(9)));
        cell.setPadding(5);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
