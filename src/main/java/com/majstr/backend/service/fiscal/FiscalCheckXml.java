package com.majstr.backend.service.fiscal;

import com.majstr.backend.service.importer.EstimateExtractor.Extracted.Line;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decoder for the {@code checkXml} the receipt lookup returns.
 *
 * <p>The format is undocumented and printed by many РРО/ПРРО vendors, so this reads <b>tolerantly</b>
 * rather than to a schema: a position is a {@code ROW} inside the body container (or, in the layout
 * that has none, a bare {@code P} element), and each field is looked up under every spelling seen in
 * the wild, as an attribute or as a child element. A field it cannot
 * find is left null and the shared receipt normalization flags it, so the master is re-asked — the
 * one thing this must never do is invent a number that ends up on a document a client signs.
 *
 * <p><b>Money and quantity are integer-scaled</b> (money x100, quantity x1000) in every sample seen.
 * A value that already carries a separator is taken literally instead, because some vendors emit
 * plain decimals. That heuristic is a guess by construction — which is why {@link FiscalQrService}
 * cross-checks the parsed lines against the QR's own total and drops them wholesale when the two
 * disagree.
 */
final class FiscalCheckXml {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("ddMMyyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    };

    private static final String[] NAME_KEYS = {"NAME", "NM"};
    private static final String[] UNIT_KEYS = {"UNITNM", "UNM", "UN"};
    private static final String[] QTY_KEYS = {"AMOUNT", "Q", "QTY"};
    private static final String[] PRICE_KEYS = {"PRICE", "PRC"};
    private static final String[] COST_KEYS = {"COST", "SM", "SUM"};

    private FiscalCheckXml() {
    }

    /** Parse the decoded {@code checkXml}, or null when it is not readable as a receipt. */
    static FiscalReceipt parse(byte[] xml) {
        Document doc = read(xml);
        if (doc == null) return null;
        Element root = doc.getDocumentElement();
        if (root == null) return null;

        return new FiscalReceipt(
                text(root, "ORGNM", "SELLER", "ORGNAME"),
                issuedAt(root),
                total(root),
                lines(root));
    }

    // ---- structure ------------------------------------------------------------

    private static List<Line> lines(Element root) {
        List<Line> out = new ArrayList<>();
        for (Element row : rows(root)) {
            String name = field(row, NAME_KEYS);
            if (name == null || name.isBlank()) continue; // a row with no name is not a position
            out.add(new Line(
                    name.trim(),
                    field(row, UNIT_KEYS),
                    scaled(field(row, QTY_KEYS), 1000),
                    scaled(field(row, PRICE_KEYS), 100),
                    "MATERIAL", // a shop receipt is goods; the master can retype a line as work
                    null));
        }
        return out;
    }

    /**
     * The POSITION rows only.
     *
     * <p>Scoped to the body container on purpose. The {@code CHECK} layout reuses {@code <ROW>} for
     * payments and taxes too ({@code CHECKPAY}, {@code PAYSYS}, {@code CHECKTAX}), and those carry a
     * {@code NAME} ("VISA", "PDV") with no quantity - so a document-wide sweep turned a perfectly good
     * receipt into a set containing incomplete lines, which {@link FiscalQrService#trustedItems} then
     * dropped WHOLESALE. A real receipt yielded zero positions. Take the body when there is one; fall
     * back to a document-wide sweep only for a layout that has no body container at all.</p>
     */
    private static List<Element> rows(Element root) {
        List<Element> out = new ArrayList<>();
        for (Element body : tagged(root, "CHECKBODY")) {
            out.addAll(tagged(body, "ROW"));
        }
        if (!out.isEmpty()) return out;
        // The RQ layout has no container: positions are bare <P> elements among printed text lines.
        List<Element> bare = tagged(root, "P");
        return bare.isEmpty() ? tagged(root, "ROW") : bare;
    }

    private static List<Element> tagged(Element root, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList found = root.getElementsByTagName(tag);
        for (int i = 0; i < found.getLength(); i++) {
            out.add((Element) found.item(i));
        }
        return out;
    }

    private static BigDecimal total(Element root) {
        for (String tag : new String[]{"CHECKTOTAL", "E"}) {
            NodeList found = root.getElementsByTagName(tag);
            if (found.getLength() == 0) continue;
            BigDecimal sum = scaled(field((Element) found.item(0), COST_KEYS), 100);
            if (sum != null) return sum;
        }
        return null;
    }

    private static LocalDate issuedAt(Element root) {
        String raw = text(root, "ORDERDATE", "DATE", "DAT");
        if (raw == null) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw.trim(), f);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    // ---- field reading --------------------------------------------------------

    /** An attribute or a direct child, under any of the given spellings. */
    private static String field(Element el, String... keys) {
        for (String key : keys) {
            String attr = el.getAttribute(key);
            if (!attr.isBlank()) return attr;
        }
        for (Node child = el.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = child.getNodeName().toUpperCase(Locale.ROOT);
            for (String key : keys) {
                if (!tag.equals(key)) continue;
                String value = child.getTextContent();
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    /** The first non-blank element with any of these tag names, anywhere in the document. */
    private static String text(Element root, String... tags) {
        for (String tag : tags) {
            NodeList found = root.getElementsByTagName(tag);
            for (int i = 0; i < found.getLength(); i++) {
                String value = found.item(i).getTextContent();
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    /**
     * A scaled integer ({@code 10000} at scale 100 is {@code 100.00}), or the literal decimal when
     * the value already carries a separator.
     */
    private static BigDecimal scaled(String raw, int scale) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().replace(',', '.').replace(" ", "");
        try {
            if (s.indexOf('.') >= 0) {
                return new BigDecimal(s);
            }
            BigDecimal v = new BigDecimal(s)
                    .divide(BigDecimal.valueOf(scale), 4, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            return v.scale() < 0 ? v.setScale(0) : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- parsing --------------------------------------------------------------

    /**
     * The bytes are parsed as-is so the document's own encoding declaration decides — the payload is
     * windows-1251 and decoding it as UTF-8 first would mangle every Ukrainian name.
     */
    private static Document read(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The document comes from outside; no doctype, no external entities, no XInclude.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            return null;
        }
    }
}
