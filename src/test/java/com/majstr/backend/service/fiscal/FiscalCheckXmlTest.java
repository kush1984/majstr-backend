package com.majstr.backend.service.fiscal;

import org.junit.jupiter.api.Test;

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
