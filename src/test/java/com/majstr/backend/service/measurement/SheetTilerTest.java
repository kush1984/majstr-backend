package com.majstr.backend.service.measurement;

import com.majstr.backend.service.ai.AiInput;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cutting one sheet into fragments we render ourselves.
 *
 * <p>The premise being tested is arithmetic, and it is the whole reason the class exists: an A3
 * sheet handed over as a PDF page arrives downscaled to ~1568 px, which leaves 8 pt dimension text
 * about 10 px tall — legible for a table, not for a run of four-digit chains. A quarter of that
 * sheet at 200 DPI is ~1800 px for a quarter of the content, so the same digits arrive 2.3× bigger.
 * If a change ever makes the fragments smaller than the provider's own downscale, this test fails
 * and the class has lost its point.</p>
 */
class SheetTilerTest {

    /** An A3 landscape sheet with one dimension-like figure on it. */
    private static byte[] a3Sheet() throws Exception {
        return sheet(PDRectangle.A4.getHeight() * 1.414f, PDRectangle.A4.getWidth() * 1.414f);
    }

    private static byte[] sheet(float widthPt, float heightPt) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                cs.newLineAtOffset(40, 40);
                cs.showText("3545 4990 13300");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage decode(AiInput input) throws Exception {
        assertThat(input).isInstanceOf(AiInput.Image.class);
        AiInput.Image image = (AiInput.Image) input;
        assertThat(image.mediaType()).isIn("image/png", "image/jpeg");
        return ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    @Test
    void oneSheetBecomesFourFragmentsEachBiggerThanTheProvidersOwnDownscale() throws Exception {
        List<List<AiInput>> fragments = SheetTiler.tiles(a3Sheet(), "read it");

        // An A3 sheet is 420×297 mm, so at ~A5 per fragment it is still four of them.
        assertThat(fragments).hasSize(4);
        for (List<AiInput> fragment : fragments) {
            // Each fragment is a ready-to-send pair: the image, then what to do with it.
            assertThat(fragment).hasSize(2);
            BufferedImage img = decode(fragment.get(0));
            assertThat(Math.max(img.getWidth(), img.getHeight()))
                    .as("a fragment must be larger than the ~1568 px a provider keeps, "
                            + "otherwise rendering it ourselves gained nothing")
                    .isGreaterThan(1568);
        }
    }

    /** A1 landscape — the format a технічний паспорт arrives on. */
    private static byte[] a1Sheet() throws Exception {
        return sheet(841f * 72f / 25.4f, 594f * 72f / 25.4f);
    }

    @Test
    void aBIGGERsheetIsCutIntoMOREfragments_notIntoBiggerOnes() throws Exception {
        // The grid used to be a fixed 2×2, which quietly meant "assume A3". Quartering an A1 sheet
        // gives fragments covering 420×297 mm each — after the provider's cap the chains are back to
        // the ~10 px that made fragments necessary in the first place, and the whole pass buys
        // nothing. The fragment count follows the PAPER now.
        List<List<AiInput>> a3 = SheetTiler.tiles(a3Sheet(), "read it");
        List<List<AiInput>> a1 = SheetTiler.tiles(a1Sheet(), "read it");

        assertThat(a1.size()).isGreaterThan(a3.size());
        // …but not without limit: past the cap a bigger sheet gets coarser fragments instead of a
        // bill that grows with paper size.
        assertThat(a1.size()).isLessThanOrEqualTo(9);
    }

    @Test
    void eachFragmentSaysWhichPartOfTheSheetItIs() throws Exception {
        // Without this the model cannot tell a top-left corner from a bottom-right one, and rooms
        // get matched to the chains of a different part of the plan.
        List<String> instructions = SheetTiler.tiles(a3Sheet(), "read it").stream()
                .map(f -> ((AiInput.Text) f.get(1)).text())
                .toList();

        assertThat(instructions).allMatch(s -> s.startsWith("read it"));
        // Said as coordinates, not as «top left quarter»: on a 3×3 grid there are no quarters, and
        // a fragment that cannot say where it sits gets its rooms matched to the wrong chains.
        assertThat(instructions).anyMatch(s -> s.contains("row 1 of 2") && s.contains("column 1 of 2"));
        assertThat(instructions).anyMatch(s -> s.contains("row 2 of 2") && s.contains("column 2 of 2"));
        assertThat(instructions).allMatch(s -> s.contains("FRAGMENT"));
    }

    @Test
    void fragmentsOverlapSoAChainOnASeamSurvivesWhole() throws Exception {
        List<List<AiInput>> fragments = SheetTiler.tiles(a3Sheet(), "read it");
        int left = decode(fragments.get(0).get(0)).getWidth();
        int right = decode(fragments.get(1).get(0)).getWidth();

        BufferedImage whole = ImageIO.read(new ByteArrayInputStream(
                ((AiInput.Image) SheetTiler.tiles(a3Sheet(), "x").get(0).get(0)).bytes()));
        assertThat(left + right)
                .as("two columns must add up to MORE than the page, or the seam cuts a chain in half")
                .isGreaterThan(whole.getWidth());
    }

    @Test
    void aBrokenFileYieldsNoFragmentsInsteadOfFailingTheImport() {
        // Fragments are an optimisation on top of a whole-page pass that already succeeded. Throwing
        // here would turn a better reading into no reading at all.
        assertThat(SheetTiler.tiles("not a pdf".getBytes(StandardCharsets.UTF_8), "read it")).isEmpty();
        assertThat(SheetTiler.tiles(new byte[0], "read it")).isEmpty();
    }
}
