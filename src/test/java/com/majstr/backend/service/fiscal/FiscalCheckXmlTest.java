package com.majstr.backend.service.fiscal;

import com.majstr.backend.service.importer.EstimateExtractor.Extracted.Line;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;

import java.nio.charset.Charset;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two {@code checkXml} row layouts seen in the wild — attributes on {@code <ROW>} and child
 * elements under {@code <P>} — plus the windows-1251 encoding the payload actually arrives in.
 *
 * <p>The format is undocumented, so these fixtures are the contract we chose to read, not one
 * anybody published. That is also why nothing here asserts the decoder is right about scaling:
 * {@link FiscalQrService#trustedItems} re-checks it against the QR's own total.
 */
class FiscalCheckXmlTest {

    private static final Charset CP1251 = Charset.forName("windows-1251");

    /**
     * A REAL receipt from the tax service (Pull&Bear, 21.08.2026), whose layout has no body
     * container: positions are bare {@code <P>} elements interleaved with printed text lines.
     */
    @Test
    void readsARealPrroReceipt() throws Exception {
        FiscalReceipt receipt = FiscalCheckXml.parse(fixture("real-prro-receipt.xml"));

        assertThat(receipt).isNotNull();
        assertThat(receipt.total()).isEqualByComparingTo("3008");
        assertThat(receipt.items()).extracting(Line::name)
                .containsExactly("ПЛЮШ", "ПЛЮШ", "Сумка M");
        assertThat(receipt.items().getLast().quantity()).isEqualByComparingTo("1");
        assertThat(receipt.items().getLast().unitPrice()).isEqualByComparingTo("10");
        assertThat(FiscalQrService.trustedItems(receipt.items(), new BigDecimal("3008"))).hasSize(3);
    }

    /**
     * A REAL receipt in the {@code CHECK} layout (RESERVED, 21.08.2026). It reuses {@code <ROW>} for
     * payments and taxes, and those rows carry a {@code NAME} ("VISA", "ПДВ") with no quantity — so a
     * document-wide sweep produced incomplete lines and {@code trustedItems} dropped the WHOLE set.
     * A real receipt therefore yielded zero positions; this pins the body-scoped read that fixed it.
     */
    @Test
    void realReceiptPaymentAndTaxRowsAreNotPositions() throws Exception {
        FiscalReceipt receipt = FiscalCheckXml.parse(fixture("real-rro-receipt.xml"));

        assertThat(receipt).isNotNull();
        assertThat(receipt.label()).isEqualTo("ДП \"ЛПП УКРАЇНА\" АТ \"ЛПП\"");
        assertThat(receipt.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(receipt.total()).isEqualByComparingTo("1011.00");
        assertThat(receipt.items()).extracting(Line::name)
                .containsExactly("026KF-89X-XXL Жіноча футболка", "H6485-XXX-ONE ПАКЕТ ПАПЕРОВИЙ")
                .doesNotContain("VISA", "ПДВ", "КАРТКА");
        // The point of the fix: the set survives the cross-check instead of being dropped wholesale.
        assertThat(FiscalQrService.trustedItems(receipt.items(), new BigDecimal("1011.00"))).hasSize(2);
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = FiscalCheckXmlTest.class.getResourceAsStream("/fiscal/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void readsAttributeRows() {
        String xml = """
                <?xml version="1.0" encoding="windows-1251"?>
                <CHECK>
                  <CHECKHEAD><ORGNM>ТОВ «Епіцентр К»</ORGNM><ORDERDATE>15082026</ORDERDATE></CHECKHEAD>
                  <CHECKBODY>
                    <ROW NAME="Шпаклівка Sniezka 25 кг" UNITNM="шт" AMOUNT="2000" PRICE="34500" COST="69000"/>
                    <ROW NAME="Грунтовка 10 л" UNITNM="шт" AMOUNT="1000" PRICE="21000" COST="21000"/>
                  </CHECKBODY>
                  <CHECKTOTAL><SUM>90000</SUM></CHECKTOTAL>
                </CHECK>
                """;

        FiscalReceipt receipt = FiscalCheckXml.parse(xml.getBytes(CP1251));

        assertThat(receipt).isNotNull();
        assertThat(receipt.label()).isEqualTo("ТОВ «Епіцентр К»");
        assertThat(receipt.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(receipt.total()).isEqualByComparingTo("900.00");
        assertThat(receipt.items()).hasSize(2);
        assertThat(receipt.items().getFirst().name()).isEqualTo("Шпаклівка Sniezka 25 кг");
        assertThat(receipt.items().getFirst().unit()).isEqualTo("шт");
        assertThat(receipt.items().getFirst().quantity()).isEqualByComparingTo("2");
        assertThat(receipt.items().getFirst().unitPrice()).isEqualByComparingTo("345");
        assertThat(receipt.items().getFirst().type()).isEqualTo("MATERIAL");
    }

    @Test
    void readsChildElementRows() {
        String xml = """
                <?xml version="1.0" encoding="windows-1251"?>
                <CHECK>
                  <SELLER>ФОП Іваненко І. І.</SELLER>
                  <DATE>2026-08-15</DATE>
                  <BODY>
                    <P><NM>Клей плитковий</NM><UNM>мішок</UNM><Q>3000</Q><PRC>18000</PRC></P>
                  </BODY>
                  <E><SM>54000</SM></E>
                </CHECK>
                """;

        FiscalReceipt receipt = FiscalCheckXml.parse(xml.getBytes(CP1251));

        assertThat(receipt).isNotNull();
        assertThat(receipt.label()).isEqualTo("ФОП Іваненко І. І.");
        assertThat(receipt.total()).isEqualByComparingTo("540.00");
        assertThat(receipt.items()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Клей плитковий");
            assertThat(line.unit()).isEqualTo("мішок");
            assertThat(line.quantity()).isEqualByComparingTo("3");
            assertThat(line.unitPrice()).isEqualByComparingTo("180");
        });
    }

    @Test
    void aValueThatAlreadyCarriesASeparatorIsTakenLiterally() {
        String xml = """
                <?xml version="1.0" encoding="windows-1251"?>
                <CHECK><CHECKBODY>
                  <ROW NAME="Фарба" UNITNM="л" AMOUNT="1.5" PRICE="249.90"/>
                </CHECKBODY></CHECK>
                """;

        FiscalReceipt receipt = FiscalCheckXml.parse(xml.getBytes(CP1251));

        assertThat(receipt.items()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("1.5");
            assertThat(line.unitPrice()).isEqualByComparingTo("249.90");
        });
    }

    @Test
    void unreadableFieldsAreLeftNullRatherThanInvented() {
        // A null unit/quantity/price is what ReceiptLines turns into an `issues` flag, so the master
        // is re-asked. Guessing here would put a made-up number on a document a client signs.
        String xml = """
                <?xml version="1.0" encoding="windows-1251"?>
                <CHECK><CHECKBODY>
                  <ROW NAME="Дрібниця"/>
                  <ROW UNITNM="шт" AMOUNT="1000"/>
                </CHECKBODY></CHECK>
                """;

        FiscalReceipt receipt = FiscalCheckXml.parse(xml.getBytes(CP1251));

        assertThat(receipt.items()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Дрібниця"); // the nameless row is not a position
            assertThat(line.unit()).isNull();
            assertThat(line.quantity()).isNull();
            assertThat(line.unitPrice()).isNull();
        });
        assertThat(receipt.total()).isNull();
        assertThat(receipt.label()).isNull();
    }

    @Test
    void rubbishIsNullNotAnException() {
        assertThat(FiscalCheckXml.parse("not xml at all".getBytes(CP1251))).isNull();
        assertThat(FiscalCheckXml.parse(new byte[0])).isNull();
    }

    @Test
    void aDoctypeIsRefused() {
        // XXE hardening: the document comes from outside, so an entity declaration must not parse
        // at all rather than be expanded.
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE CHECK [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <CHECK><CHECKBODY><ROW NAME="&x;"/></CHECKBODY></CHECK>
                """;

        assertThat(FiscalCheckXml.parse(xml.getBytes(CP1251))).isNull();
    }
}
