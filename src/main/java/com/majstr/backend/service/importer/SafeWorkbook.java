package com.majstr.backend.service.importer;

import com.majstr.backend.exception.CatalogImportException;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The one door to POI. {@link WorkbookFactory#create} inflates every part and parses every sheet into
 * objects before the first row can be read, so no cap on rows protects anything: the memory is gone by
 * then. POI's own bomb guard only looks at the ratio per entry, which leaves a 15 MB upload free to
 * become more than a gigabyte of XML, and catalog import is open to FREE accounts.
 *
 * <p>So the file is measured before POI sees it: its size, then what it really inflates to, counted
 * rather than read off headers that can lie, with the same zip reader POI uses so both see the same
 * entries. A price list of 500 rows is a few hundred kilobytes of XML, so both limits are far above any
 * real file. One workbook is parsed at a time, because what the limits bound is one parse.</p>
 *
 * <p>The inflated limit is a heap budget. POI keeps about 17 times the XML on the heap (measured: 14 MB
 * of sheet held 240 MB after parsing), so 8 MB is about 140 MB, and a file well under 1 MB can reach it.
 * Raising this is what would let one small upload take the JVM down.</p>
 */
public final class SafeWorkbook {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final long MAX_INFLATED_BYTES = 8L * 1024 * 1024;

    private static final String TOO_LARGE = "error.import.spreadsheet-too-large";
    private static final String BUSY = "error.import.busy";
    private static final String UNREADABLE = "error.import.unreadable";

    private static final Duration WAIT = Duration.ofSeconds(20);
    static final Semaphore PARSING = new Semaphore(1, true);

    private SafeWorkbook() {
    }

    @FunctionalInterface
    public interface Reader<T> {
        T read(Workbook workbook) throws Exception;
    }

    /** Also for a CSV, which has no parts to measure but is held in memory and decoded whole. */
    public static void requireSize(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new CatalogImportException(TOO_LARGE);
        }
    }

    public static <T> T read(byte[] bytes, Reader<T> reader) {
        return read(bytes, reader, WAIT);
    }

    static <T> T read(byte[] bytes, Reader<T> reader, Duration wait) {
        requireSize(bytes);
        acquire(wait);
        try {
            requireBoundedInflation(bytes);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                return reader.read(workbook);
            }
        } catch (CatalogImportException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogImportException(UNREADABLE);
        } finally {
            PARSING.release();
        }
    }

    private static void acquire(Duration wait) {
        try {
            if (!PARSING.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new CatalogImportException(BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogImportException(BUSY);
        }
    }

    /** An old .xls is not an archive: nothing in it inflates, so {@link #MAX_BYTES} already bounds it. */
    private static void requireBoundedInflation(byte[] bytes) throws IOException {
        if (FileMagic.valueOf(bytes) != FileMagic.OOXML) {
            return;
        }
        byte[] buffer = new byte[8192];
        long total = 0;
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_INFLATED_BYTES) {
                        throw new CatalogImportException(TOO_LARGE);
                    }
                }
            }
        }
    }
}
