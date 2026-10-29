package com.majstr.backend.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;

/**
 * «Складено в Majstr · majstr.pro» at the foot of every page of a client-facing PDF. No digits on
 * purpose: several tests compare a document's digits as a whole, and a page number would shift them.
 */
public final class PdfBrandFooter extends PdfPageEventHelper {

    static final String TEXT = "Складено в Majstr · majstr.pro";
    static final String URL = "https://majstr.pro/?utm_source=pdf&utm_medium=referral&utm_campaign=document_footer";
    private static final Color GREY = new Color(150, 150, 150);

    private final Font font;

    private PdfBrandFooter(PdfFontProvider fonts) {
        this.font = fonts.regular(7);
        this.font.setColor(GREY);
    }

    public static void install(PdfWriter writer, PdfFontProvider fonts) {
        writer.setPageEvent(new PdfBrandFooter(fonts));
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
        Chunk chunk = new Chunk(TEXT, font);
        chunk.setAnchor(URL);
        float x = (document.left() + document.right()) / 2;
        float y = document.bottom() - 24;
        ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER, new Phrase(chunk), x, y, 0);
    }
}
