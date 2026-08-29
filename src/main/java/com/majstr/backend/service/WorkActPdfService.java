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
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ClientType;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActKind;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders an «Акт виконаних робіт» PDF (acts iteration). A different document from the estimate PDF
 * — its own header, two distinct dates, contractor/customer requisites, an optional additional-works
 * section with a legal agreement clause, a sum-in-words, and (for INTERIM) the mandatory
 * «this is not final acceptance» disclaimer — so it lives in its own service rather than being
 * bent into {@link EstimatePdfService}. Same stack (OpenPDF, {@link PdfFontProvider}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkActPdfService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Locale UA = Locale.forLanguageTag("uk-UA");
    private static final Color HEADER_BG = new Color(230, 230, 230);
    private static final Color SECTION_BG = new Color(245, 245, 245);
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
            addTitle(document, model);
            addDatesAndObject(document, model);
            addParties(document, model);
            BigDecimal total = addItemsTable(document, model);
            BigDecimal receiptsTotal = addReceiptsTable(document, model);
            BigDecimal payable = addTotals(document, model, total, receiptsTotal);
            addSumInWords(document, payable);
            addCumulativeReference(document, model);
            addStatements(document, model);
            addSignatures(document, model);
            addDocHashFooter(document, model);
            addReceiptPhotos(document, model);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    // ---- sections ---------------------------------------------------------

    /** Tamper-evidence stamp for a SIGNED act — the SHA-256 of the canonical (unstamped) PDF. A
     *  verifier re-renders the unstamped document and compares; a changed byte changes the hash. */
    private void addDocHashFooter(Document doc, PdfModel model) throws DocumentException {
        String hash = model.docHash();
        if (hash == null || hash.isBlank()) {
            return;
        }
        Paragraph p = new Paragraph("Цифровий відбиток документа (SHA-256): " + hash,
                fonts.regular(7));
        p.setSpacingBefore(14);
        doc.add(p);
    }

    private void addTitle(Document doc, PdfModel model) throws DocumentException {
        WorkAct act = model.act();
        // Logo (PRO) on the right; title block on the left.
        PdfPTable head = new PdfPTable(2);
        head.setWidthPercentage(100);
        head.setWidths(new int[]{4, 1});

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(0);
        Paragraph title = new Paragraph("АКТ № " + act.getNumber() + " приймання-передачі виконаних робіт",
                fonts.bold(14));
        titleCell.addElement(title);
        // The stage name («Штукатурні роботи») right under the heading — how masters label
        // interim acts in real life (master feedback).
        if (notBlank(act.getTitle())) {
            titleCell.addElement(new Paragraph(act.getTitle().trim(), fonts.bold(12)));
        }
        // «(проміжний)» only when the act has no name of its own (master feedback) — with a stage
        // name the word is noise. The legal ч.3 ст.853 interim disclaimer below is UNAFFECTED: it
        // hangs on the KIND, not on this label.
        if (act.getKind() == WorkActKind.INTERIM && !notBlank(act.getTitle())) {
            titleCell.addElement(new Paragraph("(проміжний)", fonts.regular(11)));
        }
        if (notBlank(act.getContractRef())) {
            titleCell.addElement(new Paragraph("За договором: " + act.getContractRef().trim(), fonts.regular(10)));
        }
        head.addCell(titleCell);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(0);
        logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (featureGuard.isEnabled(model.contractor(), Feature.BRANDED_PDF)) {
            tryLoadLogo(model.contractor().getLogoUrl()).ifPresent(image -> {
                image.scaleToFit(80, 60);
                logoCell.addElement(image);
            });
        }
        head.addCell(logoCell);
        doc.add(head);
        doc.add(new Paragraph(" "));
    }

    private void addDatesAndObject(Document doc, PdfModel model) throws DocumentException {
        WorkAct act = model.act();
        // Two DISTINCT dates on separate lines — the compilation date is not the work period.
        doc.add(new Paragraph("Дата складання: " + DATE.format(act.getIssuedAt()), fonts.regular(10)));
        doc.add(new Paragraph("Роботи виконано в період: з " + DATE.format(act.getPeriodFrom())
                + " по " + DATE.format(act.getPeriodTo()), fonts.regular(10)));
        String docCity = model.contractor().getDocCity();
        if (notBlank(docCity)) {
            doc.add(new Paragraph("Місце складання: " + docCity.trim(), fonts.regular(10)));
        }
        Project project = model.project();
        Paragraph object = new Paragraph("Об'єкт: " + project.getName()
                + (notBlank(project.getAddress()) ? ", " + project.getAddress() : ""), fonts.regular(10));
        object.setSpacingAfter(8);
        doc.add(object);
    }

    private void addParties(Document doc, PdfModel model) throws DocumentException {
        User c = model.contractor();
        // Виконавець — legalName → companyName → fullName fallback, so the block is never blank.
        List<String> exec = new ArrayList<>();
        exec.add(firstNonBlank(c.getLegalName(), c.getCompanyName(), c.getFullName()));
        if (notBlank(c.getTaxId())) {
            exec.add("РНОКПП: " + c.getTaxId().trim());
        }
        if (notBlank(c.getLegalAddress())) {
            exec.add("Адреса: " + c.getLegalAddress().trim());
        }
        if (notBlank(c.getIban())) {
            exec.add("IBAN: " + c.getIban().trim() + (notBlank(c.getBankName()) ? ", " + c.getBankName().trim() : ""));
        }
        if (c.isVatPayer()) {
            exec.add("Платник ПДВ" + (notBlank(c.getVatId()) ? ", ІПН " + c.getVatId().trim() : ""));
        } else {
            String tax = "Не є платником ПДВ";
            if (c.getTaxGroup() != null) {
                tax += "; платник єдиного податку " + c.getTaxGroup() + "-ї групи"
                        + (c.getTaxRate() != null ? ", " + formatPercent(c.getTaxRate()) : "");
            }
            exec.add(tax);
        }
        addPartyBlock(doc, "Виконавець:", exec);

        // Замовник — by client type.
        Client client = model.client();
        List<String> cust = new ArrayList<>();
        if (client == null) {
            cust.add("—");
        } else if (client.getClientType() == ClientType.PERSON) {
            cust.add(client.getFullName());
        } else {
            cust.add(firstNonBlank(client.getLegalName(), client.getFullName()));
            if (notBlank(client.getTaxId())) {
                cust.add((client.getClientType() == ClientType.COMPANY ? "ЄДРПОУ: " : "РНОКПП: ") + client.getTaxId().trim());
            }
            if (notBlank(client.getLegalAddress())) {
                cust.add("Адреса: " + client.getLegalAddress().trim());
            }
            if (notBlank(client.getSignatoryTitle()) || notBlank(client.getSignatoryName())) {
                cust.add("В особі: " + joinNonBlank(", ", client.getSignatoryTitle(), client.getSignatoryName()));
            }
        }
        addPartyBlock(doc, "Замовник:", cust);
    }

    private void addPartyBlock(Document doc, String title, List<String> lines) throws DocumentException {
        Paragraph heading = new Paragraph(title, fonts.bold(10));
        heading.setSpacingBefore(6);
        doc.add(heading);
        for (String line : lines) {
            doc.add(new Paragraph(line, fonts.regular(10)));
        }
    }

    /** The priced table, grouped estimate → category (additional works in their own «ІІ» section).
     *  @return the act's grand total. */
    private BigDecimal addItemsTable(Document doc, PdfModel model) throws DocumentException {
        List<WorkActItem> main = model.items().stream().filter(i -> i.getEstimateItemId() != null).toList();
        List<WorkActItem> additional = model.items().stream().filter(i -> i.getEstimateItemId() == null).toList();

        Paragraph heading = new Paragraph("І. ВИКОНАНІ РОБОТИ", fonts.bold(12));
        heading.setSpacingBefore(10);
        heading.setSpacingAfter(4);
        doc.add(heading);

        PdfPTable table = newItemsTable();
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        int[] n = {0};

        // Group main works by estimate (name from the model's map), then by category within it.
        Map<UUID, List<WorkActItem>> byEstimate = new LinkedHashMap<>();
        for (WorkActItem item : main) {
            byEstimate.computeIfAbsent(item.getEstimateId(), k -> new ArrayList<>()).add(item);
        }
        boolean multiEstimate = byEstimate.size() > 1;
        for (Map.Entry<UUID, List<WorkActItem>> group : byEstimate.entrySet()) {
            if (multiEstimate) {
                String name = model.estimateNames().getOrDefault(group.getKey(), "Кошторис");
                addSpanRow(table, "Кошторис: " + name, SECTION_BG, fonts.bold(10));
            }
            total = total.add(addCategoryGrouped(table, group.getValue(), n));
        }

        if (!additional.isEmpty()) {
            addSpanRow(table, "ІІ. ДОДАТКОВІ РОБОТИ (не передбачені кошторисом)", HEADER_BG, fonts.bold(10));
            total = total.add(addCategoryGrouped(table, additional, n));
        }
        doc.add(table);
        return total;
    }

    private BigDecimal addCategoryGrouped(PdfPTable table, List<WorkActItem> items, int[] n) {
        Map<String, List<WorkActItem>> byCategory = new LinkedHashMap<>();
        for (WorkActItem item : items) {
            String category = item.getCategory() == null ? "" : item.getCategory().trim();
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item);
        }
        boolean sectioned = byCategory.size() > 1 || !byCategory.containsKey("");
        BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (Map.Entry<String, List<WorkActItem>> section : byCategory.entrySet()) {
            if (sectioned) {
                addSpanRow(table, section.getKey().isEmpty() ? NO_CATEGORY : section.getKey(), SECTION_BG, fonts.bold(10));
            }
            for (WorkActItem item : section.getValue()) {
                n[0]++;
                sum = sum.add(item.getLineTotal());
                table.addCell(textCell(String.valueOf(n[0]), Element.ALIGN_CENTER));
                table.addCell(textCell(item.getName(), Element.ALIGN_LEFT));
                table.addCell(textCell(UnitLabel.ua(item.getUnit()), Element.ALIGN_CENTER));
                table.addCell(textCell(formatQuantity(item.getQuantity()), Element.ALIGN_RIGHT));
                table.addCell(textCell(formatMoney(item.getUnitPrice()), Element.ALIGN_RIGHT));
                table.addCell(textCell(formatMoney(item.getLineTotal()), Element.ALIGN_RIGHT));
            }
        }
        return sum;
    }

    /**
     * The «ЧЕКИ ТА РАХУНКИ» section — materials the master paid for and re-bills on this act. The
     * receipt's own line items are deliberately not carried over (master feedback): a description,
     * a date and one amount per receipt, plus a subtotal. The amount is what is BILLED — paid less
     * returned (V115) — with the return spelled out under the label. Part of the CANONICAL
     * (hashed) render — unlike the live «ДОВІДКОВО» block, a receipt is a frozen copy that can
     * never change after signing.
     *
     * @return the receipts subtotal, or zero when the act has none.
     */
    private BigDecimal addReceiptsTable(Document doc, PdfModel model) throws DocumentException {
        // The MONEY table bills only non-itemized receipts: an itemized one's positions are already
        // rows of the works table above (round 2). Its photo still lands in the appendix as proof.
        List<ReceiptRow> receipts = model.receipts().stream().filter(r -> !r.itemized()).toList();
        if (receipts.isEmpty()) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        }
        boolean hasAdditional = model.items().stream().anyMatch(i -> i.getEstimateItemId() == null);
        Paragraph heading = new Paragraph((hasAdditional ? "ІІІ" : "ІІ") + ". ЧЕКИ ТА РАХУНКИ",
                fonts.bold(12));
        heading.setSpacingBefore(12);
        heading.setSpacingAfter(4);
        doc.add(heading);

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{0.6f, 6.4f, 1.6f, 1.8f});
        addColumnHeader(table, "№");
        addColumnHeader(table, "Опис");
        addColumnHeader(table, "Дата");
        addColumnHeader(table, "Сума");

        BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        int n = 0;
        for (ReceiptRow r : receipts) {
            n++;
            sum = sum.add(r.billedAmount());
            table.addCell(textCell(String.valueOf(n), Element.ALIGN_CENTER));
            table.addCell(receiptLabelCell(r));
            table.addCell(textCell(r.issuedAt() == null ? "—" : DATE.format(r.issuedAt()), Element.ALIGN_CENTER));
            table.addCell(textCell(formatMoney(r.billedAmount()), Element.ALIGN_RIGHT));
        }
        doc.add(table);
        return sum.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /** Label cell of a receipt row. A partial return (V115) is spelled out under the label rather
     *  than given a fifth column: the paper says 2000, the client is billed 1500, and the sentence
     *  in between is what stops that reading as an error when he opens the photo in the appendix. */
    private PdfPCell receiptLabelCell(ReceiptRow r) {
        if (r.returnedOrZero().signum() == 0) {
            return textCell(r.label(), Element.ALIGN_LEFT);
        }
        PdfPCell cell = new PdfPCell();
        cell.setPadding(4);
        cell.addElement(new Paragraph(r.label(), fonts.regular(10)));
        cell.addElement(new Paragraph("за чеком " + formatMoney(r.amount()) + " ₴, повернуто "
                + formatMoney(r.returnedOrZero()) + " ₴", fonts.regular(8)));
        return cell;
    }

    private BigDecimal addTotals(Document doc, PdfModel model, BigDecimal total, BigDecimal receiptsTotal)
            throws DocumentException {
        WorkAct act = model.act();
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(55);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(12);
        table.setWidths(new int[]{2, 1});

        BigDecimal advance = act.getAdvanceOffset() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING) : act.getAdvanceOffset();
        boolean hasAdvance = advance.signum() > 0;
        boolean hasReceipts = receiptsTotal.signum() > 0;
        BigDecimal grand = total.add(receiptsTotal).setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal payable = grand.subtract(advance).max(BigDecimal.ZERO).setScale(MONEY_SCALE, MONEY_ROUNDING);

        if (hasReceipts) {
            addTotalRow(table, "Разом за роботами:", formatMoney(total), false);
            addTotalRow(table, "Разом за чеками:", formatMoney(receiptsTotal), false);
        }
        addTotalRow(table, "Разом за актом:", formatMoney(grand), !hasAdvance);
        if (hasAdvance) {
            addTotalRow(table, "Зараховано авансів:", "− " + formatMoney(advance), false);
            addTotalRow(table, "До сплати:", formatMoney(payable), true);
        }
        doc.add(table);
        return payable;
    }

    /**
     * The receipt photos as an appendix on their own page(s) at the end — proof of what was bought,
     * unreadable if embedded mid-table (same choice as the estimate PDF's «ЧЕКИ» appendix). A
     * receipt with no photo is simply skipped, and a corrupt image is logged, never fatal.
     */
    private void addReceiptPhotos(Document doc, PdfModel model) throws DocumentException {
        // PDF-appendix-only toggle (master feedback): a formal printout may not want the photo
        // pages. The money table above ALWAYS renders, and the portal always shows the photos.
        if (!model.act().isShowReceiptPhotos()) {
            return;
        }
        List<ReceiptRow> withPhoto = model.receipts().stream()
                .filter(r -> r.storageKey() != null && !r.storageKey().isBlank()).toList();
        if (withPhoto.isEmpty()) {
            return;
        }
        doc.newPage();
        Paragraph heading = new Paragraph("ДОДАТОК: ФОТО ЧЕКІВ", fonts.bold(12));
        heading.setSpacingAfter(8);
        doc.add(heading);
        int n = 0;
        for (ReceiptRow r : withPhoto) {
            n++;
            try (InputStream stream = storage.open(r.storageKey()).orElse(null)) {
                if (stream == null) {
                    continue;
                }
                Image image = Image.getInstance(stream.readAllBytes());
                image.scaleToFit(500, 620);
                image.setAlignment(Element.ALIGN_CENTER);
                Paragraph caption = new Paragraph(n + ". " + r.label() + " — " + formatMoney(r.amount()),
                        fonts.regular(9));
                caption.setSpacingBefore(6);
                caption.setSpacingAfter(2);
                doc.add(caption);
                doc.add(image);
            } catch (Exception e) {
                log.warn("Could not embed act receipt image {}: {}", r.storageKey(), e.getMessage());
            }
        }
    }

    private void addSumInWords(Document doc, BigDecimal payable) throws DocumentException {
        Paragraph words = new Paragraph("Сума до сплати прописом: " + HryvniaInWords.format(payable), fonts.regular(10));
        words.setSpacingBefore(6);
        doc.add(words);
    }

    /**
     * The «ДОВІДКОВО» reference block — three object-wide money rows (виконано з початку / загалом за
     * кошторисами / залишок), NOT a per-line table (the old shape duplicated the act table on the
     * first act). {@code model.cumulative()} is null whenever the block should not render — the block
     * is off, this is the first act, or this is the canonical (hashed) render (the figures are live
     * and object-wide, so they are deliberately excluded from {@code doc_hash}). The two present
     * figures come from the SAME queries that feed the economy works axis, so PDF and app never
     * disagree.
     */
    private void addCumulativeReference(Document doc, PdfModel model) throws DocumentException {
        CumulativeReference ref = model.cumulative();
        if (ref == null) {
            return;
        }
        BigDecimal accepted = ref.accepted().setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal contracted = ref.contracted().setScale(MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal remaining = contracted.subtract(accepted).setScale(MONEY_SCALE, MONEY_ROUNDING);
        Paragraph heading = new Paragraph("ДОВІДКОВО (наростаючим підсумком по об'єкту)", fonts.bold(10));
        heading.setSpacingBefore(12);
        heading.setSpacingAfter(4);
        doc.add(heading);
        PdfPTable table = new PdfPTable(new float[]{4f, 2f});
        table.setWidthPercentage(70);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        addCumulativeRow(table, "Виконано з початку робіт:", accepted);
        addCumulativeRow(table, "Загалом за кошторисами:", contracted);
        addCumulativeRow(table, "Залишок:", remaining);
        doc.add(table);
    }

    private void addCumulativeRow(PdfPTable table, String label, BigDecimal value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, fonts.regular(10)));
        labelCell.setBorder(0);
        labelCell.setPadding(3);
        PdfPCell valueCell = new PdfPCell(new Phrase(formatMoney(value), fonts.regular(10)));
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setBorder(0);
        valueCell.setPadding(3);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addStatements(Document doc, PdfModel model) throws DocumentException {
        boolean hasAdditional = model.items().stream().anyMatch(i -> i.getEstimateItemId() == null);
        if (hasAdditional) {
            Paragraph agree = new Paragraph("Сторони підтверджують, що зазначені додаткові роботи "
                    + "погоджені Замовником та виконані за його згодою. Підписанням цього Акта Замовник "
                    + "підтверджує їх погодження та приймання.", fonts.regular(9));
            agree.setSpacingBefore(12);
            doc.add(agree);
        }
        Paragraph quality = new Paragraph("Роботи виконано в повному обсязі та з належною якістю. "
                + "Сторони претензій одна до одної не мають.", fonts.regular(10));
        quality.setSpacingBefore(12);
        doc.add(quality);

        if (model.act().getKind() == WorkActKind.INTERIM) {
            Paragraph interim = new Paragraph("Цей Акт є проміжним: він не є прийманням Об'єкта в цілому, "
                    + "не звільняє Виконавця від гарантійних зобов'язань і не позбавляє Замовника права "
                    + "заявити про приховані недоліки (ч. 3 ст. 853 ЦК України).", fonts.regular(9));
            interim.setSpacingBefore(8);
            doc.add(interim);
        }
    }

    private void addSignatures(Document doc, PdfModel model) throws DocumentException {
        PdfPTable signatures = new PdfPTable(2);
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(30);
        User c = model.contractor();
        Client client = model.client();
        signatures.addCell(signatureCell("Виконавець:", firstNonBlank(c.getLegalName(), c.getFullName())));
        String customerName = client == null ? ""
                : (client.getClientType() == ClientType.PERSON
                    ? client.getFullName()
                    : joinNonBlank(", ", client.getSignatoryTitle(), firstNonBlank(client.getSignatoryName(), client.getFullName())));
        signatures.addCell(signatureCell("Замовник:", customerName));
        doc.add(signatures);
    }

    // ---- helpers ----------------------------------------------------------

    private PdfPTable newItemsTable() throws DocumentException {
        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{0.6f, 5.4f, 1.1f, 1.2f, 1.6f, 1.8f});
        addColumnHeader(table, "№");
        addColumnHeader(table, "Найменування");
        addColumnHeader(table, "Од. вим.");
        addColumnHeader(table, "К-ть");
        addColumnHeader(table, "Ціна");
        addColumnHeader(table, "Сума");
        return table;
    }

    private void addSpanRow(PdfPTable table, String text, Color bg, com.lowagie.text.Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setColspan(table.getNumberOfColumns());
        cell.setBackgroundColor(bg);
        cell.setPadding(5);
        table.addCell(cell);
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
        com.lowagie.text.Font font = emphasize ? fonts.bold(12) : fonts.regular(11);
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

    private String formatMoney(BigDecimal value) {
        return String.format(UA, "%,.2f грн", value.setScale(MONEY_SCALE, MONEY_ROUNDING));
    }

    private String formatPercent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private String formatQuantity(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0
                ? stripped.toPlainString()
                : String.format(UA, "%,." + stripped.scale() + "f", stripped);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v.trim();
            }
        }
        return "";
    }

    private static String joinNonBlank(String sep, String... values) {
        List<String> parts = new ArrayList<>();
        for (String v : values) {
            if (notBlank(v)) {
                parts.add(v.trim());
            }
        }
        return String.join(sep, parts);
    }

    /** Input bundle. {@code client} may be null; {@code estimateNames} maps each referenced estimate
     *  id to its display name for the group headers. {@code docHash} (SHA-256 of the canonical,
     *  unstamped PDF) is set only for a SIGNED act — a cheap tamper-evidence stamp in the footer.
     *  {@code cumulative} is the «ДОВІДКОВО» reference or null (block off / first act / hashed render);
     *  it is intentionally excluded from the canonical (hashed) PDF because its figures are live and
     *  object-wide, so a later signing must not break an earlier act's stored hash. */
    public record PdfModel(
            User contractor,
            Project project,
            Client client,
            WorkAct act,
            List<WorkActItem> items,
            List<ReceiptRow> receipts,
            Map<UUID, String> estimateNames,
            String docHash,
            CumulativeReference cumulative
    ) {
        public PdfModel {
            estimateNames = estimateNames == null ? Map.of() : estimateNames;
            receipts = receipts == null ? List.of() : receipts;
        }
    }

    /** One «Чеки та рахунки» row. Frozen data straight off {@code work_act_receipt}, so unlike the
     *  «ДОВІДКОВО» figures it is safe inside the canonical (hashed) render.
     *
     *  <p>{@code amount} is what the paper says and {@code returnedAmount} what went back to the
     *  shop (V115); the table bills {@link #billedAmount()}. Both are kept, because the photo of
     *  the receipt is in the appendix and the client must be able to reconcile it.</p> */
    public record ReceiptRow(String label, LocalDate issuedAt, BigDecimal amount,
                             BigDecimal returnedAmount, String storageKey, boolean itemized) {
        public static ReceiptRow from(com.majstr.backend.entity.WorkActReceipt r) {
            return new ReceiptRow(r.getLabel(), r.getIssuedAt(), r.getAmount(), r.getReturnedAmount(),
                    r.getStorageKey(), r.isItemized());
        }

        public BigDecimal returnedOrZero() {
            return returnedAmount == null ? BigDecimal.ZERO : returnedAmount;
        }

        public BigDecimal billedAmount() {
            return amount.subtract(returnedOrZero());
        }
    }

    /** The two figures behind the «ДОВІДКОВО» block: how much work is accepted by SIGNED acts across
     *  the whole object («виконано з початку робіт») and the object's contracted total («загалом за
     *  кошторисами»). «Залишок» is their difference, computed at render. */
    public record CumulativeReference(BigDecimal accepted, BigDecimal contracted) {}
}
