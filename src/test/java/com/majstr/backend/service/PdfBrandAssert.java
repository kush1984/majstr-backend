package com.majstr.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class PdfBrandAssert {

    private PdfBrandAssert() {}

    static void assertBrandedOnEveryPage(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isPositive();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                assertThat(stripper.getText(doc)).as("page %d text", i).contains(PdfBrandFooter.TEXT);
                assertThat(linkTargets(doc.getPage(i - 1))).as("page %d links", i).contains(PdfBrandFooter.URL);
            }
        }
    }

    private static List<String> linkTargets(PDPage page) throws Exception {
        List<String> uris = new ArrayList<>();
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                uris.add(uri.getURI());
            }
        }
        return uris;
    }
}
