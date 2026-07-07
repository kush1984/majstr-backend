package com.majstr.backend.service.importer;

import com.majstr.backend.dto.CatalogImportParseResponse;
import com.majstr.backend.dto.CatalogImportParseResponse.ParsedRow;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.CatalogImportException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogImportParserTest {

    private final CatalogImportParser parser = new CatalogImportParser();

    private ParsedRow row(CatalogImportParseResponse r, String namePart) {
        return r.rows().stream().filter(x -> x.name().contains(namePart)).findFirst().orElseThrow();
    }

    @Test
    void parsesDirtyXlsx_skipsHeaderAndTotals_normalizesUnitsAndPrices() throws Exception {
        byte[] xlsx = xlsx(new String[][]{
                {"Назва", "Од.", "Ціна"},                              // header → skipped
                {"Штроба в бетоні", "пог.м", "1 200,50 грн"},          // LINEAR_METER, 1200.50
                {"Демонтаж перегородок", "кв.м", "150"},               // M2, 150 (WORK — no marker)
                {"Грунтовка глибокого проникнення", "шт", "80"},       // MATERIAL by name marker
                {"Фарбування стін", "відро", "200"},                   // unit unrecognized → issue
                {"Разом", "", "1630,50"},                              // total → skipped
                {"", "", ""},                                          // empty → skipped
        });

        CatalogImportParseResponse r = parser.parseFile("prices.xlsx", xlsx);

        assertThat(r.rows()).hasSize(4);
        assertThat(r.skippedRows()).isEqualTo(3);
        assertThat(r.guessedMapping().nameCol()).isEqualTo(0);
        assertThat(r.guessedMapping().unitCol()).isEqualTo(1);
        assertThat(r.guessedMapping().priceCol()).isEqualTo(2);

        ParsedRow shtroba = row(r, "Штроба");
        assertThat(shtroba.unit()).isEqualTo(Unit.LINEAR_METER);
        assertThat(shtroba.price()).isEqualByComparingTo("1200.50");
        assertThat(shtroba.issues()).isEmpty();

        assertThat(row(r, "Грунтовка").type()).isEqualTo(ItemType.MATERIAL);
        assertThat(row(r, "Демонтаж").type()).isEqualTo(ItemType.WORK);

        ParsedRow paint = row(r, "Фарбування");
        assertThat(paint.unit()).isNull();
        assertThat(paint.issues()).contains("unit");
    }

    @Test
    void parsesCsvWithSemicolonDelimiter() {
        String csv = "Назва;Од.;Ціна\nШтроба;пог.м;120\nДемонтаж;кв.м;150";
        CatalogImportParseResponse r = parser.parseFile("p.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.rows()).hasSize(2);
        assertThat(row(r, "Штроба").unit()).isEqualTo(Unit.LINEAR_METER);
    }

    @Test
    void parsesPastedTabSeparatedText() {
        String pasted = "Штроба\tпог.м\t120\nДемонтаж\tшт\t150";
        CatalogImportParseResponse r = parser.parseText(pasted);
        assertThat(r.rows()).hasSize(2);
        assertThat(row(r, "Демонтаж").unit()).isEqualTo(Unit.PIECE);
    }

    @Test
    void decodesWindows1251Csv() {
        byte[] cp1251 = "Штроба;шт;100".getBytes(Charset.forName("windows-1251"));
        CatalogImportParseResponse r = parser.parseFile("old.csv", cp1251);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).name()).isEqualTo("Штроба");
    }

    @Test
    void rejectsMoreThan500Rows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            sb.append("Позиція ").append(i).append("\tшт\t10\n");
        }
        assertThatThrownBy(() -> parser.parseText(sb.toString()))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.too-many-rows");
    }

    @Test
    void parsePrice_handlesMessyFormats() {
        assertThat(CatalogImportParser.parsePrice("1 200,50 грн")).isEqualByComparingTo("1200.50");
        assertThat(CatalogImportParser.parsePrice("1200.50")).isEqualByComparingTo("1200.50");
        assertThat(CatalogImportParser.parsePrice("₴900")).isEqualByComparingTo("900");
        assertThat(CatalogImportParser.parsePrice("1.200,50")).isEqualByComparingTo("1200.50");
        assertThat(CatalogImportParser.parsePrice("1,200.50")).isEqualByComparingTo("1200.50");
        assertThat(CatalogImportParser.parsePrice("немає")).isNull();
        assertThat(CatalogImportParser.parsePrice("")).isNull();
        assertThat(CatalogImportParser.parsePrice("-5")).isNull();
    }

    private static byte[] xlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Прайс");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
