package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportCommitRequest;
import com.majstr.backend.dto.CatalogImportCommitRequest.CommitItem;
import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.dto.EstimateImportCommitRequest;
import com.majstr.backend.dto.EstimateImportCommitResponse;
import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateImportParseResponse.ParsedItem;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateService.ImportEstimateData;
import com.majstr.backend.service.EstimateService.ImportEstimateData.ImportItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Orchestrates the LLM estimate import (PRO-gated). {@code parse} feeds the uploaded
 * Excel/CSV (rendered to a text grid) or photo (base64) to {@link ClaudeEstimateExtractor},
 * normalizes units/types, and returns a review proposal — the file is never persisted.
 * {@code commit} creates the estimate on the target object (via {@link EstimateService})
 * and upserts the ticked positions into the master's catalog (via {@link CatalogImportService}),
 * both in one transaction. Feature gate + ownership are enforced here / downstream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstimateImportService {

    private static final int MAX_GRID_ROWS = 400;
    private static final int MAX_GRID_COLS = 40;

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final ClaudeEstimateExtractor extractor;
    private final EstimateService estimateService;
    private final CatalogImportService catalogImportService;

    private final DataFormatter dataFormatter = new DataFormatter(Locale.forLanguageTag("uk-UA"));

    /** Parse an uploaded file/photo into a review proposal. Nothing is written; the bytes are discarded. */
    public EstimateImportParseResponse parse(UUID ownerId, String filename, String contentType, byte[] bytes) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.ESTIMATE_IMPORT);

        String imageMediaType = imageMediaType(filename, contentType);
        ClaudeEstimateExtractor.Extracted extracted;
        if (imageMediaType != null) {
            extracted = extractor.extractFromImage(imageMediaType, bytes);
        } else if (isSpreadsheet(filename, contentType)) {
            extracted = extractor.extractFromText(spreadsheetToText(bytes));
        } else if (isCsv(filename, contentType)) {
            extracted = extractor.extractFromText(decode(bytes));
        } else {
            throw new CatalogImportException("error.import.unsupported");
        }
        return toReview(extracted);
    }

    /** Commit the confirmed import: create the estimate on the object + upsert the catalog. */
    @Transactional
    public EstimateImportCommitResponse commit(UUID ownerId, EstimateImportCommitRequest req) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.ESTIMATE_IMPORT);

        List<ImportItem> importItems = req.items().stream()
                .map(i -> new ImportItem(i.type(), i.name(), i.category(), i.unit(), i.quantity(), i.unitPrice()))
                .toList();
        EstimateResponse estimate = estimateService.createFromImport(
                req.projectId(),
                new ImportEstimateData(req.estimateName(), req.depositAmount(), importItems),
                ownerId);

        // Catalog side-effect: only the positions the master ticked on the review screen.
        List<CommitItem> catalogItems = req.items().stream()
                .filter(EstimateImportCommitRequest.CommitItem::toCatalog)
                .map(i -> new CommitItem(i.name(), i.unit(), i.unitPrice(), i.type(), i.catalogPolicy()))
                .toList();
        CatalogImportCommitResponse catalog = catalogItems.isEmpty()
                ? new CatalogImportCommitResponse(0, 0, 0)
                : catalogImportService.commit(ownerId,
                        new CatalogImportCommitRequest(catalogItems, null, DedupPolicy.SKIP));

        log.info("Estimate import for {} → estimate {} ({} items); catalog created={}, updated={}, skipped={}",
                ownerId, estimate.id(), importItems.size(),
                catalog.created(), catalog.updated(), catalog.skipped());
        return new EstimateImportCommitResponse(
                estimate.id(), estimate.total(),
                catalog.created(), catalog.updated(), catalog.skipped());
    }

    // ---- extraction → review mapping ------------------------------------------

    private EstimateImportParseResponse toReview(ClaudeEstimateExtractor.Extracted extracted) {
        List<ParsedItem> items = new ArrayList<>();
        for (ClaudeEstimateExtractor.Extracted.Line line : extracted.items()) {
            Unit unit = UnitNormalizer.normalize(line.unit());
            ItemType type = parseType(line.type());
            BigDecimal quantity = line.quantity();
            BigDecimal unitPrice = line.unitPrice();

            List<String> issues = new ArrayList<>();
            if (unit == null) issues.add("unit");
            if (quantity == null || quantity.signum() <= 0) issues.add("quantity");
            if (unitPrice == null || unitPrice.signum() <= 0) issues.add("price");

            items.add(new ParsedItem(
                    line.name().trim(),
                    unit,
                    quantity,
                    unitPrice,
                    type,
                    blankToNull(line.category()),
                    issues));
        }
        return new EstimateImportParseResponse(items, extracted.depositAmount());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ItemType parseType(String raw) {
        if (raw == null) {
            return ItemType.WORK;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("MAT") || s.contains("МАТЕР")) {
            return ItemType.MATERIAL;
        }
        return ItemType.WORK;
    }

    // ---- input type detection + text extraction -------------------------------

    /** The image media type to send to Claude, or null if this isn't an image upload. */
    private static String imageMediaType(String filename, String contentType) {
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

    private static boolean isSpreadsheet(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return true;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("spreadsheet") || ct.contains("ms-excel");
    }

    private static boolean isCsv(String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return true;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("csv");
    }

    /** First sheet → tab-separated text grid (capped) for the model to read. */
    private String spreadsheetToText(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes);
             Workbook wb = WorkbookFactory.create(in)) {
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
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    /** UTF-8, falling back to windows-1251 when the bytes aren't valid UTF-8 (old Excel CSVs). */
    private static String decode(byte[] bytes) {
        String asUtf8 = new String(bytes, StandardCharsets.UTF_8);
        if (asUtf8.indexOf('�') >= 0) {
            try {
                return new String(bytes, Charset.forName("windows-1251"));
            } catch (Exception ignore) {
                return asUtf8;
            }
        }
        return asUtf8;
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }
}
