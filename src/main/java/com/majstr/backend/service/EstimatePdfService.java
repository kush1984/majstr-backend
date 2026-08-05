package com.majstr.backend.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EstimatePdfService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Locale UA = Locale.forLanguageTag("uk-UA");
    private static final Color HEADER_BG = new Color(230, 230, 230);
    /** Section rows sit between the column header and the lines, so a shade lighter. */
    private static final Color SECTION_BG = new Color(245, 245, 245);
    /** Matches the label the editor shows for unfiled lines. */
    private static final String NO_CATEGORY = "Без категорії";

    private final PdfFontProvider fonts;
    private final FeatureGuard featureGuard;
    private final StorageService storage;

    public byte[] render(PdfModel model) throws DocumentException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        PdfWriter.getInstance(document, out);
        document.open();
        try {
            addHeader(document, model);
            addProjectInfo(document, model);
            addItemsTable(document, "РОБОТИ", model.workItems());
            addItemsTable(document, "МАТЕРІАЛИ", model.materialItems());
            addTotals(document, model);
            addNotesAndSignatures(document, model);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------- sections

    private void addHeader(Document doc, PdfModel model) throws DocumentException, IOException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new int[]{4, 1});

        PdfPCell info = new PdfPCell();
        info.setBorder(0);
        info.addElement(new Paragraph(model.contractor().getCompanyName(), fonts.bold(16)));
        info.addElement(new Paragraph("Тел.: " + model.contractor().getPhone(), fonts.regular(10)));
        header.addCell(info);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(0);
        logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        // Logo lives behind the BRANDED_PDF feature flag — if the user is
        // on a plan without it, render the rest of the PDF normally and
        // skip the logo. Throws are intentionally NOT used here.
        if (featureGuard.isEnabled(model.contractor(), Feature.BRANDED_PDF)) {
            tryLoadLogo(model.contractor().getLogoUrl()).ifPresent(image -> {
                image.scaleToFit(80, 60);
                logoCell.addElement(image);
            });
        }
        header.addCell(logoCell);
        doc.add(header);
        doc.add(new Paragraph(" "));
    }

    private void addProjectInfo(Document doc, PdfModel model) throws DocumentException {
        Estimate estimate = model.estimate();
        Project project = model.project();
        Client client = model.client();

        PdfPTable info = new PdfPTable(2);
        info.setWidthPercentage(100);
        info.setWidths(new int[]{1, 2});
        info.setSpacingAfter(8);

        addInfoRow(info, "Об'єкт:", project.getName());
        addInfoRow(info, "Адреса:", project.getAddress());
        if (client != null) {
            addInfoRow(info, "Клієнт:", client.getFullName() + ", " + client.getPhone());
        }
        // Kyiv, not UTC: an estimate created after midnight local time would otherwise be
        // dated to the previous day on the document the client keeps.
        addInfoRow(info, "Дата:", DATE_FORMAT.format(
                estimate.getCreatedAt().atZone(LocalizationConfig.ZONE).toLocalDate()));
        if (estimate.getValidUntil() != null) {
            addInfoRow(info, "Дійсний до:", DATE_FORMAT.format(estimate.getValidUntil()));
        }
        doc.add(info);
    }

    /**
     * One type's table — «РОБОТИ» or «МАТЕРІАЛИ» — split into the sections the master arranged.
     *
     * <p>A section is the run of lines sharing a category. The caller passes them already ordered by
     * {@code sortOrder}, so grouping in first-appearance order reproduces exactly what the master
     * dragged into place on screen, without the PDF needing to know anything about ordering.</p>
     *
     * <p>An estimate whose lines carry no category at all renders exactly as it did before sections
     * existed: no headers, no subtotals. A master who does not file their lines should not be shown a
     * «Без категорії» heading over the whole document, and a subtotal identical to the grand total
     * would be noise on a client-facing page.</p>
     */
    private void addItemsTable(Document doc, String title, List<EstimateItem> items) throws DocumentException {
        if (items.isEmpty()) {
            return;
        }
        Paragraph heading = new Paragraph(title, fonts.bold(12));
        heading.setSpacingBefore(8);
        heading.setSpacingAfter(4);
        doc.add(heading);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{6f, 1.2f, 1.2f, 1.6f, 1.8f});

        addColumnHeader(table, "Назва");
        addColumnHeader(table, "Од.");
        addColumnHeader(table, "К-сть");
        addColumnHeader(table, "Ціна");
        addColumnHeader(table, "Сума");

        // Every line by id, so a percentage row can name the line it is measured against.
        Map<UUID, EstimateItem> byId = new LinkedHashMap<>();
        for (EstimateItem item : items) {
            byId.put(item.getId(), item);
        }

        Map<String, List<EstimateItem>> sections = new LinkedHashMap<>();
        for (EstimateItem item : items) {
            String category = item.getCategory() == null ? "" : item.getCategory().trim();
            sections.computeIfAbsent(category, k -> new ArrayList<>()).add(item);
        }
        boolean sectioned = sections.size() > 1 || !sections.containsKey("");

        for (Map.Entry<String, List<EstimateItem>> section : sections.entrySet()) {
            if (sectioned) {
                addSectionHeader(table, section.getKey().isEmpty() ? NO_CATEGORY : section.getKey());
            }
            BigDecimal sectionTotal = BigDecimal.ZERO;
            for (EstimateItem item : section.getValue()) {
                BigDecimal lineTotal = amountOf(item);
                sectionTotal = sectionTotal.add(lineTotal);
                table.addCell(textCell(item.getName(), Element.ALIGN_LEFT));
                table.addCell(textCell(UnitLabel.ua(item.getUnit()), Element.ALIGN_CENTER));
                table.addCell(textCell(formatQuantity(item.getQuantity()), Element.ALIGN_RIGHT));
                // A percentage has no price per unit. «500 ₴/%» meant nothing to the client and
                // «0,00» would read as free work, so the cell says what the percentage is OF —
                // the same wording the app and the portal show, so the three cannot disagree.
                table.addCell(textCell(priceCell(item, byId), Element.ALIGN_RIGHT));
                table.addCell(textCell(formatMoney(lineTotal), Element.ALIGN_RIGHT));
            }
            if (sectioned) {
                addSectionSubtotal(table, sectionTotal);
            }
        }
        doc.add(table);
    }

    /**
     * Full-width row naming a section, lighter than the column header so that still reads as one.
     *
     * Centred, per the owner: with the whole row spanning the table, a left-aligned heading sits under
     * the «Назва» column and reads as another line rather than as a divider. Measured — the title
     * lands at x≈276 of a 595pt page against x=44 for a line — because it is the CELL's alignment that
     * governs here, not the phrase's.
     */
    private void addSectionHeader(PdfPTable table, String name) {
        PdfPCell cell = new PdfPCell(new Phrase(name, fonts.bold(10)));
        cell.setColspan(5);
        cell.setBackgroundColor(SECTION_BG);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5);
        table.addCell(cell);
    }

    /** What this stage costs — what a master is actually asked on site, and what a client compares. */
    private void addSectionSubtotal(PdfPTable table, BigDecimal total) {
        // Bold, same weight as the figure beside it: this is the number a master is asked on site,
        // so it should read as a total rather than as a caption.
        PdfPCell label = new PdfPCell(new Phrase("Разом по розділу", fonts.bold(10)));
        label.setColspan(4);
        label.setHorizontalAlignment(Element.ALIGN_RIGHT);
        label.setPadding(4);
        table.addCell(label);

        PdfPCell value = new PdfPCell(new Phrase(formatMoney(total), fonts.bold(10)));
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        value.setPadding(4);
        table.addCell(value);
    }

    private void addTotals(Document doc, PdfModel model) throws DocumentException {
        Totals totals = computeTotals(model.items());

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(50);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(12);
        table.setWidths(new int[]{2, 1});

        addTotalRow(table, "Сума робіт:", formatMoney(totals.works), false);
        addTotalRow(table, "Сума матеріалів:", formatMoney(totals.materials), false);
        addTotalRow(table, "РАЗОМ:", formatMoney(totals.grand), true);
        // Deposit / balance, only when a deposit was recorded (client-facing).
        BigDecimal deposit = model.estimate().getDepositAmount();
        if (deposit != null) {
            BigDecimal balance = totals.grand.subtract(deposit).max(BigDecimal.ZERO)
                    .setScale(MONEY_SCALE, MONEY_ROUNDING);
            addTotalRow(table, "Завдаток:", formatMoney(deposit), false);
            addTotalRow(table, "ЗАЛИШОК:", formatMoney(balance), true);
        }
        doc.add(table);
    }

    private void addNotesAndSignatures(Document doc, PdfModel model) throws DocumentException {
        if (model.estimate().getNotes() != null && !model.estimate().getNotes().isBlank()) {
            Paragraph notesTitle = new Paragraph("Умови:", fonts.bold(11));
            notesTitle.setSpacingBefore(16);
            doc.add(notesTitle);
            Paragraph notes = new Paragraph(model.estimate().getNotes(), fonts.regular(10));
            notes.setSpacingAfter(20);
            doc.add(notes);
        }

        PdfPTable signatures = new PdfPTable(2);
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(30);
        signatures.addCell(signatureCell("Підрядник:", model.contractor().getFullName()));
        signatures.addCell(signatureCell("Клієнт:", model.client() == null ? "" : model.client().getFullName()));
        doc.add(signatures);
    }

    // ------------------------------------------------------------------- helpers

    private Optional<Image> tryLoadLogo(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try (InputStream stream = storage.open(key).orElse(null)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(Image.getInstance(stream.readAllBytes()));
        } catch (Exception e) {
            log.warn("Could not load contractor logo {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void addInfoRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, fonts.bold(10)));
        labelCell.setBorder(0);
        labelCell.setPaddingBottom(2);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, fonts.regular(10)));
        valueCell.setBorder(0);
        valueCell.setPaddingBottom(2);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addColumnHeader(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, fonts.bold(10)));
        cell.setBackgroundColor(HEADER_BG);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private PdfPCell textCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, fonts.regular(10)));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        return cell;
    }

    private void addTotalRow(PdfPTable table, String label, String value, boolean emphasize) {
        Font font = emphasize ? fonts.bold(12) : fonts.regular(11);
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        labelCell.setBorder(emphasize ? 1 : 0);
        labelCell.setPadding(5);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setBorder(emphasize ? 1 : 0);
        valueCell.setPadding(5);
        if (emphasize) {
            labelCell.setBackgroundColor(HEADER_BG);
            valueCell.setBackgroundColor(HEADER_BG);
        }
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private PdfPCell signatureCell(String label, String name) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPadding(10);
        cell.addElement(new Paragraph(label, fonts.regular(10)));
        cell.addElement(new Paragraph(" ", fonts.regular(10)));
        cell.addElement(new Paragraph("__________________________", fonts.regular(10)));
        cell.addElement(new Paragraph(name, fonts.regular(9)));
        return cell;
    }

    private String formatMoney(BigDecimal value) {
        return String.format(UA, "%,.2f грн", value.setScale(MONEY_SCALE, MONEY_ROUNDING));
    }

    private String formatQuantity(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0
                ? stripped.toPlainString()
                : String.format(UA, "%,." + stripped.scale() + "f", stripped);
    }

    /**
     * The line's money — the amount the server stored (V88), never recomputed here.
     *
     * <p>The PDF is the document a client is quoted from. Re-deriving the arithmetic in a second
     * place is how a printed sheet ends up disagreeing with the screen it was printed from, and a
     * percentage of the estimate's own subtotal cannot be derived per row at all.</p>
     */
    private static BigDecimal amountOf(EstimateItem item) {
        return item.getLineTotal() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING)
                : item.getLineTotal();
    }

    /** What goes in the price column: a price, or — for «%» — what the percentage is measured OF. */
    private String priceCell(EstimateItem item, Map<UUID, EstimateItem> byId) {
        if (item.getUnit() != Unit.PERCENT) {
            return formatMoney(item.getUnitPrice());
        }
        PercentBaseKind kind = item.getPercentBaseKind() == null
                ? PercentBaseKind.MANUAL : item.getPercentBaseKind();
        return switch (kind) {
            case MANUAL -> "від " + formatMoney(item.getUnitPrice());
            case TOTAL -> "від суми кошторису";
            case POSITION -> {
                EstimateItem base = item.getPercentBaseItemId() == null
                        ? null : byId.get(item.getPercentBaseItemId());
                yield base == null ? "база видалена" : "від «" + base.getName() + "»";
            }
        };
    }

    private Totals computeTotals(List<EstimateItem> items) {
        BigDecimal works = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal materials = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (EstimateItem item : items) {
            BigDecimal line = amountOf(item);
            if (item.getType() == ItemType.WORK) {
                works = works.add(line);
            } else {
                materials = materials.add(line);
            }
        }
        return new Totals(works, materials, works.add(materials));
    }

    private record Totals(BigDecimal works, BigDecimal materials, BigDecimal grand) {}

    /**
     * Input bundle. {@code client} may be null when no client is linked.
     */
    public record PdfModel(
            User contractor,
            Project project,
            Client client,
            Estimate estimate,
            List<EstimateItem> items
    ) {
        public List<EstimateItem> workItems() {
            return items.stream().filter(i -> i.getType() == ItemType.WORK).toList();
        }

        public List<EstimateItem> materialItems() {
            return items.stream().filter(i -> i.getType() == ItemType.MATERIAL).toList();
        }
    }
}
