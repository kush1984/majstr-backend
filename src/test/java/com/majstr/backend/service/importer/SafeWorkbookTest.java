package com.majstr.backend.service.importer;

import com.majstr.backend.exception.CatalogImportException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeWorkbookTest {

    private static byte[] realWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Ціни").createRow(0).createCell(0).setCellValue("Штроба");
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Many parts, each small enough for a per-entry cap and each under POI's own ratio guard's notice
     * until it is opened: only their SUM is what would take the heap.
     */
    private static byte[] archive(int entries, int bytesEach) throws Exception {
        byte[] content = new byte[bytesEach];
        Arrays.fill(content, (byte) 'a');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i < entries; i++) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + i + ".xml"));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void readsARealWorkbook() throws Exception {
        String first = SafeWorkbook.read(realWorkbook(),
                wb -> wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());

        assertThat(first).isEqualTo("Штроба");
    }

    @Test
    void aSmallFileThatInflatesPastTheLimitNeverReachesPoi() throws Exception {
        byte[] bomb = archive(5, 5 * 1024 * 1024);
        assertThat(bomb.length).isLessThan(SafeWorkbook.MAX_BYTES);

        assertThatThrownBy(() -> SafeWorkbook.read(bomb, wb -> {
            throw new AssertionError("POI must not have been handed this file");
        })).isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.spreadsheet-too-large");
    }

    @Test
    void aFileOverTheLimitIsRefusedByItsSize() {
        byte[] huge = new byte[SafeWorkbook.MAX_BYTES + 1];

        assertThatThrownBy(() -> SafeWorkbook.read(huge, wb -> "never"))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.spreadsheet-too-large");
    }

    @Test
    void garbageIsUnreadableNotAServerError() {
        byte[] garbage = "це не таблиця".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SafeWorkbook.read(garbage, wb -> "never"))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.unreadable");
    }

    @Test
    void aReaderThatThrowsIsUnreadable() throws Exception {
        byte[] workbook = realWorkbook();

        assertThatThrownBy(() -> SafeWorkbook.read(workbook, wb -> {
            throw new IllegalStateException("broken sheet");
        })).isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.unreadable");
    }

    @Test
    void aSecondParseWaitsAndThenAnswersBusy() throws Exception {
        byte[] workbook = realWorkbook();
        SafeWorkbook.PARSING.acquire();
        try {
            assertThatThrownBy(() -> SafeWorkbook.read(workbook, wb -> "never", Duration.ofMillis(50)))
                    .isInstanceOf(CatalogImportException.class)
                    .hasMessage("error.import.busy");
        } finally {
            SafeWorkbook.PARSING.release();
        }
    }

    @Test
    void aFailedParseGivesItsTurnBack() throws Exception {
        byte[] garbage = "це не таблиця".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> SafeWorkbook.read(garbage, wb -> "never", Duration.ofMillis(50)))
                    .hasMessage("error.import.unreadable");
        }

        String read = SafeWorkbook.read(realWorkbook(), wb -> "read", Duration.ofMillis(50));
        assertThat(read).isEqualTo("read");
    }
}
