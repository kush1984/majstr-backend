package com.majstr.backend.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.majstr.backend.entity.Project;

import java.awt.Color;

/**
 * «Навчальний приклад Majstr» on everything a client could read from the training sample (V197): the
 * portal, the preview and both PDFs, a signed one included (the snapshot records it). The sample works
 * fully, so without the mark it would be a free third object a master could send for real.
 */
public final class SampleMark {

    public static final String TEXT = "Навчальний приклад Majstr";
    private static final Color AMBER = new Color(0xF5, 0x82, 0x1F);
    private static final Color AMBER_SOFT = new Color(0xFF, 0xF1, 0xE3);

    private SampleMark() {
    }

    /** A full-width band at the top of the page — nothing when the object is not the sample. */
    public static void addTo(Document doc, Project project, PdfFontProvider fonts) throws DocumentException {
        if (project == null || !project.isSample()) {
            return;
        }
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell(new Paragraph(TEXT + " — не є документом для клієнта", fonts.bold(11)));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBackgroundColor(AMBER_SOFT);
        cell.setBorderColor(AMBER);
        cell.setPadding(6);
        band.addCell(cell);
        band.setSpacingAfter(8);
        doc.add(band);
    }
}
