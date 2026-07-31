package com.majstr.backend.service.measurement;

import com.majstr.backend.service.ai.AiInput;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one PDF sheet ourselves and hands it over in FRAGMENTS, at a resolution we choose.
 *
 * <p>Why this exists, measured rather than assumed: a designer's sheet is A3, and the dimension
 * chains on it are set in 8 pt. A provider given a PDF page downscales it to roughly 1568 px on the
 * long edge, which leaves those digits about <strong>10 px</strong> tall — and they come in runs
 * («535 800 925 1180») where one misread digit silently becomes a wrong wall. The rooms table on
 * the same sheet is 10 pt and reads fine, which is exactly the failure the master reports: room
 * names and areas come back correct, every dimension comes back 0.</p>
 *
 * <p>Cutting the page into four overlapping quarters at 200 DPI gives each fragment its own 1568 px
 * budget, so the same digits arrive 25–30 px tall. Nothing is invented and nothing is enhanced —
 * it is the same page, just not thrown away before the model sees it.</p>
 *
 * <p>The overlap matters: a dimension chain that happens to sit on a seam would be cut in half in
 * both neighbours. At 8 % it lands whole in at least one fragment, and the merge tolerates the same
 * figure arriving twice.</p>
 */
@Slf4j
final class SheetTiler {

    /**
     * Chosen against the provider's own downscale, not for its own sake: at 200 DPI an A3 sheet is
     * 3307×2339 px, so a 2×2 fragment is ~1800 px — just above the 1568 px the provider keeps, and
     * therefore the most detail that survives. Going higher only pays for pixels that get thrown
     * away again.
     */
    static final int DPI = 200;
    /**
     * What a fragment's long edge should come out as, in pixels.
     *
     * <p>Just above the 1568 px the provider keeps, so the fragment survives its downscale intact
     * and not one pixel more is paid for. This is what {@link #dpiFor} solves the DPI from, and it
     * is why a bigger sheet does NOT cost more memory: nine fragments of an A0 cover 396 mm each,
     * which at a flat 200 DPI would be 3118 px — over twice what the provider keeps, rendered from
     * a 236 MB page image, and then thrown away. Solving for the fragment instead bounds the whole
     * page at ~74 MB whatever the paper, while every fragment still clears 1568 px.</p>
     */
    private static final int TARGET_TILE_PX = 1700;
    /**
     * How much PAPER one fragment may cover, in millimetres — roughly an A5 patch.
     *
     * <p>The grid used to be a fixed 2×2, which silently meant "assume A3". It is not always A3: a
     * technical passport comes on A1, and a whole-floor plan on A0. Quartering an A1 sheet gives
     * fragments covering 420×297 mm each, so after the provider's 1568 px cap the chains are back to
     * the same ~10 px that made the whole exercise necessary. Sizing by paper instead keeps the
     * detail constant — about 7.5 px per millimetre — whatever the sheet.</p>
     */
    private static final int TILE_MM_WIDE = 210;
    private static final int TILE_MM_HIGH = 150;
    /** Beyond this the cost stops being worth it; a bigger sheet then gets coarser fragments. */
    private static final int MAX_TILES = 9;
    private static final double OVERLAP = 0.08;
    /** A raster page can be enormous; a fragment past this goes as JPEG instead of PNG. */
    private static final int PNG_BUDGET_BYTES = 4 * 1024 * 1024;

    private SheetTiler() {
    }

    /**
     * The sheet's first page as overlapping fragments sized by the PAPER, each carrying an
     * instruction that says WHICH part of the sheet it is — without that the model cannot tell a
     * top-left corner from a bottom-right one, and rooms get matched to the wrong chains.
     *
     * @param instruction what to do with the fragment; the position is appended to it
     * @return one ready-to-send input list per fragment (the image plus its instruction), or an
     *         empty list if the page cannot be rendered — a broken PDF must degrade to the
     *         single-call path, never fail the import
     */
    static List<List<AiInput>> tiles(byte[] pdf, String instruction) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.getNumberOfPages() == 0) {
                return List.of();
            }
            var page = doc.getPage(0).getMediaBox();
            int widthMm = Math.round(page.getWidth() * 25.4f / 72f);
            int heightMm = Math.round(page.getHeight() * 25.4f / 72f);
            int cols = Math.max(1, (int) Math.ceil((double) widthMm / TILE_MM_WIDE));
            int rows = Math.max(1, (int) Math.ceil((double) heightMm / TILE_MM_HIGH));
            while (cols * rows > MAX_TILES && (cols > 1 || rows > 1)) {
                // Give up density on the longer side first — a sheet is usually wider than tall.
                if (cols >= rows) cols--;
                else rows--;
            }
            int dpi = dpiFor(widthMm, heightMm, cols, rows);
            log.info("Sheet {}x{} mm → {}x{} fragments at {} DPI (~{} MB page image)",
                    widthMm, heightMm, cols, rows, dpi,
                    Math.round(widthMm / 25.4 * dpi * (heightMm / 25.4 * dpi) * 4 / 1048576));
            BufferedImage full = new PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.RGB);
            return cut(full, instruction, cols, rows);
        } catch (Exception e) {
            // Rendering is an optimisation, never a gate: the whole-page call already happened.
            log.warn("Sheet rendering for fragments failed ({}) — staying with the whole-page pass",
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * The DPI at which the LARGEST fragment lands near {@link #TARGET_TILE_PX}, never above
     * {@link #DPI}.
     *
     * <p>The cap is what keeps an A3 sheet behaving exactly as before (its 210 mm fragment wants
     * 206 DPI, so it stays at 200). The solve is what stops an A1 or an A0 from rendering a
     * 118–236 MB page image whose extra pixels the provider immediately discards — the container
     * runs on a fraction of its RAM as heap, so that was an out-of-memory waiting for the first
     * master to upload a технічний паспорт.</p>
     */
    private static int dpiFor(int widthMm, int heightMm, int cols, int rows) {
        double longestTileMm = Math.max((double) widthMm / cols, (double) heightMm / rows);
        if (longestTileMm <= 0) {
            return DPI;
        }
        return Math.min(DPI, (int) Math.ceil(TARGET_TILE_PX * 25.4 / longestTileMm));
    }

    private static List<List<AiInput>> cut(BufferedImage full, String instruction, int cols, int rows) {
        int w = full.getWidth();
        int h = full.getHeight();
        int tileW = (int) Math.ceil((double) w / cols);
        int tileH = (int) Math.ceil((double) h / rows);
        int padX = (int) (tileW * OVERLAP);
        int padY = (int) (tileH * OVERLAP);

        List<List<AiInput>> out = new ArrayList<>(cols * rows);
        int n = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = Math.max(0, col * tileW - padX);
                int y = Math.max(0, row * tileH - padY);
                int cw = Math.min(w - x, tileW + 2 * padX);
                int ch = Math.min(h - y, tileH + 2 * padY);
                if (cw <= 0 || ch <= 0) continue;
                byte[] bytes;
                String mediaType = "image/png";
                try {
                    bytes = encode(full.getSubimage(x, y, cw, ch), "png");
                    if (bytes.length > PNG_BUDGET_BYTES) {
                        bytes = encode(full.getSubimage(x, y, cw, ch), "jpg");
                        mediaType = "image/jpeg";
                    }
                } catch (Exception e) {
                    log.warn("Fragment {} could not be encoded ({})", ++n, e.getMessage());
                    continue;
                }
                out.add(AiInput.image(mediaType, bytes,
                        instruction + "\n\nTHIS IMAGE IS FRAGMENT " + (++n) + " OF " + (cols * rows)
                                + " of ONE sheet — " + position(row, col, cols, rows) + ". Fragments overlap "
                                + "slightly, so a dimension chain may also appear in a neighbour. "
                                + "Report ONLY what you can see here; rooms whose contour is cut "
                                + "off at the edge of this fragment must be named in \"uncertain\"."));
            }
        }
        return out;
    }

    /** Where this fragment sits, in words a reader can act on: «row 2 of 3, column 1 of 3». */
    private static String position(int row, int col, int cols, int rows) {
        String vertical = rows == 1 ? "the full height"
                : "row " + (row + 1) + " of " + rows + " (top to bottom)";
        String horizontal = cols == 1 ? "the full width"
                : "column " + (col + 1) + " of " + cols + " (left to right)";
        return vertical + ", " + horizontal;
    }

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage src = img;
        if ("jpg".equals(format) && img.getType() != BufferedImage.TYPE_INT_RGB) {
            // JPEG cannot carry an alpha channel — writing one produces a black fragment.
            src = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            src.createGraphics().drawImage(img, 0, 0, null);
        }
        if (!ImageIO.write(src, format, out)) {
            throw new IllegalStateException("no ImageIO writer for " + format);
        }
        return out.toByteArray();
    }
}
