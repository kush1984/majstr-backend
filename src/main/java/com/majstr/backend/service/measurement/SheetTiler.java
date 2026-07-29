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
    static final int COLS = 2;
    static final int ROWS = 2;
    private static final double OVERLAP = 0.08;
    /** A raster page can be enormous; a fragment past this goes as JPEG instead of PNG. */
    private static final int PNG_BUDGET_BYTES = 4 * 1024 * 1024;

    private SheetTiler() {
    }

    /**
     * The sheet's first page as {@link #COLS}×{@link #ROWS} overlapping fragments, each carrying an
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
            BufferedImage full = new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.RGB);
            return cut(full, instruction);
        } catch (Exception e) {
            // Rendering is an optimisation, never a gate: the whole-page call already happened.
            log.warn("Sheet rendering for fragments failed ({}) — staying with the whole-page pass",
                    e.getMessage());
            return List.of();
        }
    }

    private static List<List<AiInput>> cut(BufferedImage full, String instruction) {
        int w = full.getWidth();
        int h = full.getHeight();
        int tileW = (int) Math.ceil((double) w / COLS);
        int tileH = (int) Math.ceil((double) h / ROWS);
        int padX = (int) (tileW * OVERLAP);
        int padY = (int) (tileH * OVERLAP);

        List<List<AiInput>> out = new ArrayList<>(COLS * ROWS);
        int n = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
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
                        instruction + "\n\nTHIS IMAGE IS FRAGMENT " + (++n) + " OF " + (COLS * ROWS)
                                + " of ONE sheet — " + position(row, col) + ". Fragments overlap "
                                + "slightly, so a dimension chain may also appear in a neighbour. "
                                + "Report ONLY what you can see here; rooms whose contour is cut "
                                + "off at the edge of this fragment must be named in \"uncertain\"."));
            }
        }
        return out;
    }

    private static String position(int row, int col) {
        String vertical = ROWS == 1 ? "full height" : (row == 0 ? "TOP" : "BOTTOM");
        String horizontal = COLS == 1 ? "full width" : (col == 0 ? "LEFT" : "RIGHT");
        return vertical + " " + horizontal + " quarter";
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
