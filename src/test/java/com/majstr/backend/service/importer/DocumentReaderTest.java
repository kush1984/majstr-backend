package com.majstr.backend.service.importer;

import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.service.ai.AiInput;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which files we read, and how each reaches the model. */
class DocumentReaderTest {

    private final DocumentReader reader = new DocumentReader();

    private static byte[] pdf(int pages, String textPerPage) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (textPerPage != null) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                        cs.newLineAtOffset(40, 700);
                        cs.showText(textPerPage);
                        cs.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aPdfWithATextLayerGoesAsText() throws Exception {
        String line = "Plastering walls m2 120 x 350 = 42000; tiling floor m2 30 x 600 = 18000; ".repeat(4);

        List<AiInput> content = reader.read("koshtorys.pdf", "application/pdf", pdf(1, line), "Extract.");

        assertThat(content).singleElement().isInstanceOf(AiInput.Text.class);
        assertThat(((AiInput.Text) content.getFirst()).text()).contains("Plastering walls").startsWith("Extract.");
    }

    @Test
    void aScanWithNoTextLayerGoesAsTheDocumentItself() throws Exception {
        List<AiInput> content = reader.read("scan.pdf", "application/pdf", pdf(2, null), "Extract.");

        assertThat(content.getFirst()).isInstanceOf(AiInput.Pdf.class);
    }

    @Test
    void aPdfIsKnownByItsBytesEvenUnderAWrongName() throws Exception {
        List<AiInput> content = reader.read("file.bin", "application/octet-stream", pdf(1, null), "Extract.");

        assertThat(content.getFirst()).isInstanceOf(AiInput.Pdf.class);
    }

    @Test
    void tooManyPagesIsRefused() throws Exception {
        byte[] big = pdf(DocumentReader.MAX_PDF_PAGES + 1, "x");

        assertThatThrownBy(() -> reader.read("big.pdf", "application/pdf", big, "Extract."))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.too-many-document-pages");
    }

    @Test
    void aPhotoAndACsvAreReadAsBefore() {
        assertThat(reader.read("p.jpg", "image/jpeg", new byte[] {1, 2}, "Extract.").getFirst())
                .isInstanceOf(AiInput.Image.class);
        List<AiInput> csv = reader.read("prices.csv", "text/csv",
                "Шпаклювання;м2;150".getBytes(StandardCharsets.UTF_8), "Extract.");
        assertThat(((AiInput.Text) csv.getFirst()).text()).contains("Шпаклювання;м2;150");
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> reader.read("notes.txt", "text/plain", "x".getBytes(), "Extract."))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.unsupported");
    }
}
