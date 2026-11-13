package com.majstr.backend.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.majstr.backend.dto.PublicPriceListView;
import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The master's price list as a PDF (V205) — the same view the client's page shows, on A4, so the two can
 * never disagree. The logo prints only on a plan with {@code BRANDED_PDF}, as on the estimate; the caller
 * decides that and passes no key otherwise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceListPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Color HEADER_BG = new Color(230, 230, 230);
    private static final Color SECTION_BG = new Color(245, 245, 245);

    private final PdfFontProvider fonts;
    private final StorageService storage;

    /** @param logoKey the storage key of the logo to print, or null for none */
    public record PdfModel(PublicPriceListView view, String logoKey) {}

    public byte[] render(PdfModel model) throws DocumentException {
        Image logo = tryLoadLogo(model.logoKey()).orElse(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        PdfBrandFooter.install(PdfWriter.getInstance(document, out), fonts);
        document.open();
        try {
            addHeader(document, model.view(), logo);
            for (PublicPriceListView.Trade trade : model.view().trades()) {
                addTrade(document, trade);
            }
            if (model.view().trades().isEmpty()) {
                Paragraph empty = new Paragraph("Майстер ще не додав робіт до прайсу.", fonts.regular(10));
                empty.setSpacingBefore(14);
                document.add(empty);
            }
            Paragraph note = new Paragraph("Остаточна вартість — у кошторисі після замірів.", fonts.regular(9));
            note.setSpacingBefore(14);
            document.add(note);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private void addHeader(Document doc, PublicPriceListView view, Image logo) throws DocumentException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new int[]{4, 1});
        PdfPCell info = new PdfPCell();
        info.setBorder(0);
        info.addElement(new Paragraph(view.contractor().companyName(), fonts.bold(16)));
        if (view.contractor().phone() != null) {
            info.addElement(new Paragraph("Тел.: " + view.contractor().phone(), fonts.regular(10)));
        }
        header.addCell(info);
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(0);
        logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (logo != null) {
            logo.scaleToFit(80, 60);
            logoCell.addElement(logo);
        }
        header.addCell(logoCell);
        doc.add(header);

        Paragraph title = new Paragraph("ЦІНИ НА РОБОТИ", fonts.bold(14));
        title.setSpacingBefore(12);
        doc.add(title);
        doc.add(new Paragraph("станом на " + view.date().format(DATE), fonts.regular(10)));
    }

    private void addTrade(Document doc, PublicPriceListView.Trade trade) throws DocumentException {
        Paragraph heading = new Paragraph(trade.name(), fonts.bold(12));
        heading.setSpacingBefore(16);
        heading.setSpacingAfter(6);
        doc.add(heading);

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new int[]{30, 6, 9});
        table.setHeaderRows(1);
        header(table, "Робота");
        header(table, "Од.");
        header(table, "Ціна, грн");
        for (PublicPriceListView.Section section : trade.sections()) {
            if (section.category() != null) {
                PdfPCell cell = new PdfPCell(new Phrase(section.category(), fonts.bold(9)));
                cell.setColspan(3);
                cell.setBackgroundColor(SECTION_BG);
                cell.setPadding(5);
                table.addCell(cell);
            }
            for (PublicPriceListView.Item item : section.items()) {
                PdfPCell name = new PdfPCell();
                name.setPadding(5);
                name.addElement(new Paragraph(item.name(), fonts.regular(9)));
                if (item.description() != null) {
                    name.addElement(new Paragraph(item.description(), fonts.regular(7)));
                }
                table.addCell(name);
                cell(table, item.unit(), Element.ALIGN_CENTER);
                cell(table, money(item.price()), Element.ALIGN_RIGHT);
            }
        }
        doc.add(table);
    }

    static String money(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        String pattern = value.stripTrailingZeros().scale() > 0 ? "#,##0.00" : "#,##0";
        return new DecimalFormat(pattern, symbols).format(value);
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
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private Optional<Image> tryLoadLogo(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try (InputStream stream = storage.open(key).orElse(null)) {
            if (stream == null) {
                return Optional.empty();
            }
            byte[] bytes = stream.readAllBytes();
            if (ImageBounds.exceeded(bytes)) {
                log.warn("Skipping contractor logo {}: its dimensions are too large", key);
                return Optional.empty();
            }
            return Optional.of(Image.getInstance(bytes));
        } catch (Exception e) {
            log.warn("Could not load contractor logo {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
