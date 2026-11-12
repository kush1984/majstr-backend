package com.majstr.backend.service.importer;

import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.service.ai.AiInput;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Turns an uploaded estimate or price list into what the model reads — the ONE place that knows
 * which files we accept: a spreadsheet or a CSV (as a text grid), a PDF (its text layer when it
 * has one, the document itself when it is a scan) and a photo.
 *
 * <p>A PDF with a real text layer — what estimate software exports — goes as TEXT: cheaper, and
 * the model reads numbers off text more reliably than off a rendered page. A scan has no text
 * layer, so the pages go as a document block and the model reads them as it reads a photo.</p>
 */
@Slf4j
@Component
public class DocumentReader {

    static final int MAX_GRID_ROWS = 400;
    static final int MAX_GRID_COLS = 40;
    /** An estimate or a price list, not a drawing set: twenty pages is a large one already. */
    static final int MAX_PDF_PAGES = 20;
    /** Below this many characters a PDF's text layer is a scan's stray OCR, not the document. */
    static final int MIN_TEXT_LAYER = 200;

    private final DataFormatter dataFormatter = new DataFormatter(Locale.forLanguageTag("uk-UA"));

    /** The document as model input, followed by {@code instruction}. Throws for a file we do not read. */
    public List<AiInput> read(String filename, String contentType, byte[] bytes, String instruction) {
        String image = imageMediaType(filename, contentType);
        if (image != null) {
            return AiInput.image(image, bytes, instruction);
        }
        if (isPdf(filename, contentType, bytes)) {
            String text = pdfText(bytes);
            return text != null
                    ? AiInput.text(instruction + "\n\nThe document's text, page by page:\n\n" + text)
                    : AiInput.pdf(bytes, instruction);
        }
        if (isSpreadsheet(filename, contentType)) {
            return AiInput.text(instruction + "\n\nThe spreadsheet as a grid:\n\n" + spreadsheetToText(bytes));
        }
        if (isCsv(filename, contentType)) {
            SafeWorkbook.requireSize(bytes);
            return AiInput.text(instruction + "\n\nThe spreadsheet as a grid:\n\n" + decode(bytes));
        }
        throw new CatalogImportException("error.import.unsupported");
    }

    static boolean isPdf(String filename, String contentType, byte[] bytes) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean magic = bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        return magic || name.endsWith(".pdf") || ct.equals("application/pdf");
    }

    /** The text layer, or null for a scan (or a file pdfbox cannot open — the model then tries). */
    private String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new CatalogImportException("error.import.too-many-document-pages");
            }
            String text = new PDFTextStripper().getText(doc).strip();
            return text.length() >= MIN_TEXT_LAYER ? text : null;
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PDF text extraction failed ({}), sending the document itself", e.getMessage());
            return null;
        }
    }

    static String imageMediaType(String filename, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("image/jpeg") || ct.equals("image/jpg")) return "image/jpeg";
        if (ct.equals("image/png")) return "image/png";
        if (ct.equals("image/webp")) return "image/webp";
        if (ct.equals("image/gif")) return "image/gif";
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return null;
    }

    static boolean isSpreadsheet(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return true;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("spreadsheet") || ct.contains("ms-excel");
    }

    static boolean isCsv(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return true;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("csv");
    }

    /** First sheet → tab-separated text grid (capped) for the model to read. */
    private String spreadsheetToText(byte[] bytes) {
        return SafeWorkbook.read(bytes, wb -> {
            if (wb.getNumberOfSheets() == 0) {
                throw new CatalogImportException("error.import.unreadable");
            }
            Sheet sheet = wb.getSheetAt(0);
            StringBuilder sb = new StringBuilder();
            int rows = 0;
            for (Row row : sheet) {
                if (rows++ >= MAX_GRID_ROWS) break;
                short last = row.getLastCellNum();
                for (int c = 0; c < Math.min(last, MAX_GRID_COLS); c++) {
                    if (c > 0) sb.append('\t');
                    Cell cell = row.getCell(c);
                    sb.append(cell == null ? "" : dataFormatter.formatCellValue(cell).trim());
                }
                sb.append('\n');
            }
            return sb.toString();
        });
    }

    /** UTF-8, falling back to windows-1251 when the bytes aren't valid UTF-8 (old Excel CSVs). */
    static String decode(byte[] bytes) {
        String asUtf8 = new String(bytes, StandardCharsets.UTF_8);
        if (asUtf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(bytes, Charset.forName("windows-1251"));
            } catch (Exception ignore) {
                return asUtf8;
            }
        }
        return asUtf8;
    }
}
