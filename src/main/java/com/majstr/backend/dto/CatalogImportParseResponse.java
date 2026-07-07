package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of parsing an uploaded/pasted price list — a proposal, never written to
 * the DB. The client shows it on the review screen, lets the master fix cells /
 * remap columns, then posts the confirmed list to {@code /import/commit}.
 *
 * <p>{@code grid} is the raw cell matrix (capped) so the front end can re-derive
 * rows locally when the master reassigns which column is name/price/unit — no file
 * re-upload. {@code rows} are the parser's best guess using {@code guessedMapping}.</p>
 */
public record CatalogImportParseResponse(
        List<List<String>> grid,
        List<ParsedRow> rows,
        int skippedRows,
        Mapping guessedMapping
) {
    /** One candidate catalog position. {@code issues} non-empty = highlight for review
     *  (e.g. unit not recognized, price not parsed). */
    public record ParsedRow(
            int gridRow,
            String name,
            Unit unit,
            BigDecimal price,
            ItemType type,
            List<String> issues
    ) {}

    /** Zero-based column indices the heuristic chose (any may be null if not found). */
    public record Mapping(Integer nameCol, Integer priceCol, Integer unitCol) {}
}
