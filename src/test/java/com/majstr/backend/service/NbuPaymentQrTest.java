package com.majstr.backend.service;

import com.majstr.backend.dto.PayTo;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** V217 — the NBU payment QR (format 002) and what the portal's «Оплатити» offers. */
class NbuPaymentQrTest {

    private static final String IBAN = "UA213223130000026007233566001";

    private static String[] lines(String link) {
        assertThat(link).startsWith(NbuPaymentQr.PREFIX);
        String data = link.substring(NbuPaymentQr.PREFIX.length());
        assertThat(data).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        return new String(Base64.getUrlDecoder().decode(data), Charset.forName("windows-1251")).split("\n", -1);
    }

    @Test
    void format002_thirteenLinesInTheirOrder_noAmount() {
        String[] l = lines(NbuPaymentQr.link("Іваненко Іван Іванович", "UA21 3223 1300 0002 6007 2335 6600 1",
                "1234567890", "Оплата за ремонтні роботи, об’єкт «Квартира»"));

        assertThat(l).containsExactly("BCD", "002", "2", "UCT", "", "Іваненко Іван Іванович", IBAN, "",
                "1234567890", "", "", "Оплата за ремонтні роботи, об’єкт «Квартира»", "");
    }

    @Test
    void textOutsideWindows1251IsDropped_notSentAsQuestionMarks() {
        String[] l = lines(NbuPaymentQr.link("Іван 🔧", IBAN, "1234567890", "Ремонт ₴ «Дім» ʼ\nдруга"));
        assertThat(l[5]).isEqualTo("Іван");
        assertThat(l[11]).isEqualTo("Ремонт «Дім» друга");
    }

    @Test
    void noQrWithoutAValidIbanCodeOrName() {
        assertThat(NbuPaymentQr.link("Іван", "UA12", "1234567890", "x")).isNull();
        assertThat(NbuPaymentQr.link("Іван", IBAN, null, "x")).isNull();
        assertThat(NbuPaymentQr.link("Іван", IBAN, "123", "x")).isNull();
        assertThat(NbuPaymentQr.link(null, IBAN, "1234567890", "x")).isNull();
        assertThat(NbuPaymentQr.link("Іван", IBAN, "12345678", "x")).as("ЄДРПОУ, 8 digits").isNotNull();
    }

    @Test
    void payTo_nothingFilled_isNull_soThePortalShowsNoButton() {
        assertThat(PayoutDetails.of(User.builder().fullName("Іван").build(), project())).isNull();
    }

    @Test
    void payTo_cardAndLinkWithoutRequisites_noQr() {
        User owner = User.builder().fullName("Іван Петренко").payoutCard("4111111111111111")
                .payoutLink("https://www.liqpay.ua/pay/x").build();

        PayTo pay = PayoutDetails.of(owner, project());

        assertThat(pay.recipientName()).isEqualTo("Іван Петренко");
        assertThat(pay.card()).isEqualTo("4111111111111111");
        assertThat(pay.iban()).isNull();
        assertThat(pay.qrUrl()).isNull();
        assertThat(pay.linkHost()).isEqualTo("www.liqpay.ua");
        assertThat(pay.purpose()).isEqualTo("Оплата за ремонтні роботи, об’єкт «Квартира»");
    }

    @Test
    void payTo_legalNameIbanAndCode_makeTheQr() {
        User owner = User.builder().fullName("Іван").legalName("ФОП Петренко Іван")
                .iban("ua21 3223 1300 0002 6007 2335 6600 1").taxId("1234567890").build();

        PayTo pay = PayoutDetails.of(owner, project());

        assertThat(pay.recipientName()).isEqualTo("ФОП Петренко Іван");
        assertThat(pay.iban()).isEqualTo(IBAN);
        assertThat(lines(pay.qrUrl())[5]).isEqualTo("ФОП Петренко Іван");
    }

    @Test
    void cardNumbersAreLuhnChecked() {
        assertThat(PayoutDetails.luhn("4111111111111111")).isTrue();
        assertThat(PayoutDetails.luhn("4111111111111112")).isFalse();
        assertThat(PayoutDetails.normalizeCard(" 4111-1111 1111-1111 ")).isEqualTo("4111111111111111");
        assertThat(PayoutDetails.normalizeCard("  ")).isNull();
    }

    @Test
    void theQrPngIsAPng() {
        byte[] png = QrPng.of(NbuPaymentQr.link("Іван", IBAN, "1234567890", "Оплата"));
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    private static Project project() {
        return Project.builder().name("Квартира").build();
    }
}
