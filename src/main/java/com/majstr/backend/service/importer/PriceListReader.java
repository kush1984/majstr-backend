package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportParseResponse;
import com.majstr.backend.dto.CatalogImportParseResponse.Mapping;
import com.majstr.backend.dto.CatalogImportParseResponse.ParsedRow;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A price list that is not a table — a PDF or a photo — read by the model into the SAME review
 * proposal the spreadsheet parser gives ({@link CatalogImportParseResponse}): a three-column grid
 * (name · price · unit as written) the review screen can remap, and the rows. Behind the same
 * feature as the estimate import, since it is the same reading.
 */
@Service
@RequiredArgsConstructor
public class PriceListReader {

    private final FeatureGuard featureGuard;
    private final UserRepository userRepository;
    private final DocumentReader documentReader;
    private final EstimateExtractor extractor;

    /** Whether this upload is read by the model rather than the spreadsheet parser. */
    public static boolean needsReading(String filename, String contentType, byte[] bytes) {
        return DocumentReader.isPdf(filename, contentType, bytes)
                || DocumentReader.imageMediaType(filename, contentType) != null;
    }

    public CatalogImportParseResponse read(UUID ownerId, String filename, String contentType, byte[] bytes) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.ESTIMATE_IMPORT);
        EstimateExtractor.Extracted extracted = extractor.extract(documentReader.read(filename, contentType, bytes,
                "This is a contractor's PRICE LIST. Extract every position with its unit price; quantity is 0."));
        List<List<String>> grid = new ArrayList<>();
        List<ParsedRow> rows = new ArrayList<>();
        for (EstimateExtractor.Extracted.Line line : extracted.items()) {
            Unit unit = UnitNormalizer.normalize(line.unit());
            BigDecimal price = line.unitPrice() != null && line.unitPrice().signum() > 0 ? line.unitPrice() : null;
            List<String> issues = new ArrayList<>();
            if (unit == null) issues.add("unit");
            if (price == null) issues.add("price");
            grid.add(List.of(line.name(), price == null ? "" : price.toPlainString(),
                    line.unit() == null ? "" : line.unit()));
            rows.add(new ParsedRow(grid.size() - 1, line.name().trim(), unit, price, type(line.type()), issues));
        }
        return new CatalogImportParseResponse(grid, rows, 0, new Mapping(0, 1, 2));
    }

    private static ItemType type(String raw) {
        return raw != null && raw.trim().toUpperCase(Locale.ROOT).startsWith("MAT") ? ItemType.MATERIAL : ItemType.WORK;
    }
}
