package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportParseResponse;
import com.majstr.backend.dto.CatalogImportParseResponse.Mapping;
import com.majstr.backend.dto.CatalogImportParseResponse.ParsedRow;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.CatalogImportException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic parser for a master's own price list — .xlsx/.xls (Apache POI),
 * .csv (auto delimiter + UTF-8/windows-1251), or pasted tab-separated text. No AI,
 * no external calls. Turns a messy sheet into a review proposal: raw grid + guessed
 * column mapping + candidate rows, skipping headers/totals/section rows.
 *
 * <p>Nothing is written to the DB and the file is never stored — parse in, structure
 * out, bytes discarded.</p>
 */
@Service
public class CatalogImportParser {

    static final int MAX_ROWS = 500;
    /** Headroom read beyond {@link #MAX_ROWS} so the downstream "too many rows" check still
     *  sees an over-limit sheet and can refuse it. Shared by the reader and {@code capGrid}
     *  so the two can never drift apart. */
    private static final int GRID_READ_MARGIN = 50;
    private static final int MAX_COLS = 40;
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000"); // 100M — sanity ceiling

    // Rows whose name-cell is one of these are service rows, not positions.
    private static final Set<String> TOTAL_MARKERS = Set.of(
            "разом", "всього", "усього", "итого", "сума", "сумма", "total", "підсумок", "загалом");
    private static final Set<String> HEADER_WORDS = Set.of(
            "назва", "найменування", "наименование", "name", "ціна", "цена", "price", "вартість",
            "од", "одиниця", "ед", "единица", "unit", "кількість", "кол-во", "qty", "№", "n", "п/п", "пп");
    // Name contains one of these → suggest MATERIAL (a hint the master can flip).
    private static final String[] MATERIAL_MARKERS = {
            "клей", "суміш", "сумиш", "мішок", "мешок", "ґрунтовк", "грунтовк", "фарб", "шпакл", "шпатл",
            "кабель", "провід", "провод", "труб", "профіл", "профил", "плитк", "цемент", "пісок", "песок",
            "гіпсокартон", "гипсокартон", "саморіз", "самарез", "дюбел", "герметик", "штукатурк", "лак",
            "утеплюв", "пінопласт", "пенопласт", "ламінат", "ламинат", "лінолеум", "линолеум", "матеріал"};

    private final DataFormatter dataFormatter = new DataFormatter(Locale.forLanguageTag("uk-UA"));

    /** Parse an uploaded spreadsheet/CSV file (chosen by extension, sniffed by POI). */
    public CatalogImportParseResponse parseFile(String filename, byte[] bytes) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<List<String>> grid = lower.endsWith(".csv")
                ? readCsv(bytes)
                : readWorkbook(bytes);
        return build(grid);
    }

    /** Parse pasted text (tab-separated, as copied from Excel/Sheets; CSV as fallback). */
    public CatalogImportParseResponse parseText(String text) {
        if (text == null || text.isBlank()) {
            throw new CatalogImportException("error.import.empty");
        }
        return build(splitDelimited(text, detectDelimiter(text)));
    }

    // ---- grid extraction --------------------------------------------------

    private List<List<String>> readWorkbook(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            List<List<String>> grid = new ArrayList<>();
            if (sheet == null) {
                return grid;
            }
            for (Row row : sheet) {
                // Stop READING at the cap instead of building the whole grid and rejecting it
                // afterwards: a zip-compressed .xlsx expanding to hundreds of thousands of rows
                // was fully materialised into memory first (any registered account can upload
                // one). The margin keeps the "too many rows" check downstream meaningful — it
                // still sees more than MAX_ROWS and refuses. Mirrors the sibling
                // EstimateImportService.spreadsheetToText, which already did this.
                if (grid.size() >= MAX_ROWS + GRID_READ_MARGIN) {
                    break;
                }
                List<String> cells = new ArrayList<>();
                short last = row.getLastCellNum();
                for (int c = 0; c < Math.min(last, MAX_COLS); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : dataFormatter.formatCellValue(cell).trim());
                }
                grid.add(cells);
            }
            return grid;
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    private List<List<String>> readCsv(byte[] bytes) {
        String text = decode(bytes);
        return splitDelimited(text, detectDelimiter(text));
    }

    /** UTF-8, falling back to windows-1251 when the bytes aren't valid UTF-8 (old Excel CSVs). */
    private String decode(byte[] bytes) {
        Charset utf8 = StandardCharsets.UTF_8;
        String asUtf8 = new String(bytes, utf8);
        // The Unicode replacement char means invalid UTF-8 sequences — retry cp1251.
        if (asUtf8.indexOf('�') >= 0) {
            try {
                return new String(bytes, Charset.forName("windows-1251"));
            } catch (Exception ignore) {
                return asUtf8;
            }
        }
        return asUtf8;
    }

    private char detectDelimiter(String text) {
        String sample = text.length() > 4000 ? text.substring(0, 4000) : text;
        int tabs = count(sample, '\t');
        int semis = count(sample, ';');
        int commas = count(sample, ',');
        if (tabs >= semis && tabs >= commas && tabs > 0) return '\t';
        if (semis >= commas && semis > 0) return ';';
        if (commas > 0) return ',';
        return '\t';
    }

    private List<List<String>> splitDelimited(String text, char delim) {
        List<List<String>> grid = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : line.split(java.util.regex.Pattern.quote(String.valueOf(delim)), -1)) {
                cells.add(stripQuotes(cell).trim());
                if (cells.size() >= MAX_COLS) break;
            }
            grid.add(cells);
        }
        return grid;
    }

    // ---- heuristics + hygiene --------------------------------------------

    private CatalogImportParseResponse build(List<List<String>> grid) {
        int cols = grid.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) {
            return new CatalogImportParseResponse(grid, List.of(), 0, new Mapping(null, null, null));
        }
        Mapping mapping = guessMapping(grid, cols);
        List<ParsedRow> rows = new ArrayList<>();
        int skipped = 0;
        for (int r = 0; r < grid.size(); r++) {
            List<String> row = grid.get(r);
            String name = cell(row, mapping.nameCol());
            if (isServiceRow(name)) {
                skipped++;
                continue;
            }
            BigDecimal price = parsePrice(cell(row, mapping.priceCol()));
            Unit unit = UnitNormalizer.normalize(cell(row, mapping.unitCol()));
            // A row with a name but neither price nor unit reads as a section header.
            if (price == null && unit == null) {
                skipped++;
                continue;
            }
            List<String> issues = new ArrayList<>();
            if (price == null) issues.add("price");
            if (unit == null) issues.add("unit");
            rows.add(new ParsedRow(r, name, unit, price, guessType(name), issues));
        }
        if (rows.size() > MAX_ROWS) {
            throw new CatalogImportException("error.import.too-many-rows");
        }
        return new CatalogImportParseResponse(capGrid(grid), rows, skipped, mapping);
    }

    /** Score every column; pick the name / price / unit columns independently. */
    private Mapping guessMapping(List<List<String>> grid, int cols) {
        double[] textScore = new double[cols];
        int[] numeric = new int[cols];
        int[] unitHits = new int[cols];
        for (List<String> row : grid) {
            for (int c = 0; c < cols; c++) {
                String v = cell(row, c);
                if (v.isEmpty()) continue;
                boolean isNum = parsePrice(v) != null;
                if (isNum) {
                    numeric[c]++;
                } else {
                    textScore[c] += Math.min(v.length(), 60);
                }
                if (UnitNormalizer.normalize(v) != null) {
                    unitHits[c]++;
                }
            }
        }
        Integer nameCol = argmax(textScore);
        Integer priceCol = argmaxInt(numeric, nameCol);
        Integer unitCol = argmaxInt(unitHits, nameCol, priceCol);
        return new Mapping(nameCol, priceCol, unitCol);
    }

    private boolean isServiceRow(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (TOTAL_MARKERS.contains(n) || HEADER_WORDS.contains(n)) return true;
        // Pure number / ordinal ("1", "12.") — a row index, not a position name.
        return n.replace(".", "").chars().allMatch(Character::isDigit) && !n.isBlank();
    }

    private ItemType guessType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String marker : MATERIAL_MARKERS) {
            if (n.contains(marker)) return ItemType.MATERIAL;
        }
        return ItemType.WORK;
    }

    /** Parse "1 200,50 грн" / "1200.50" / "₴900" → BigDecimal, or null if not a number. */
    static BigDecimal parsePrice(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT)
                .replace("грн", "").replace("₴", "").replace("uah", "").replace("руб", "")
                .replace(" ", "").replace(" ", "").trim();
        if (s.isEmpty()) return null;
        // A value with letters left after stripping the currency is text, not a price —
        // so a NAME like "Позиція 1" is never mistaken for a number.
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c) || c == ',' || c == '.' || c == '-') digits.append(c);
        }
        String cleaned = digits.toString();
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            // Both present: the right-most separator is the decimal one; the other is a grouping sep.
            char decimal = lastComma > lastDot ? ',' : '.';
            char grouping = decimal == ',' ? '.' : ',';
            cleaned = cleaned.replace(String.valueOf(grouping), "").replace(decimal, '.');
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }
        try {
            BigDecimal value = new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
            if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- small helpers ----------------------------------------------------

    private static List<List<String>> capGrid(List<List<String>> grid) {
        return grid.size() <= MAX_ROWS + GRID_READ_MARGIN
                ? grid
                : grid.subList(0, MAX_ROWS + GRID_READ_MARGIN);
    }

    private static String cell(List<String> row, Integer col) {
        if (col == null || col < 0 || col >= row.size()) return "";
        return row.get(col) == null ? "" : row.get(col);
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static Integer argmax(double[] scores) {
        int best = -1;
        double max = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > max) { max = scores[i]; best = i; }
        }
        return best < 0 ? null : best;
    }

    private static Integer argmaxInt(int[] scores, Integer... exclude) {
        int best = -1;
        int max = 0;
        for (int i = 0; i < scores.length; i++) {
            if (isExcluded(i, exclude)) continue;
            if (scores[i] > max) { max = scores[i]; best = i; }
        }
        return best < 0 ? null : best;
    }

    private static boolean isExcluded(int i, Integer[] exclude) {
        for (Integer e : exclude) {
            if (e != null && e == i) return true;
        }
        return false;
    }
}
