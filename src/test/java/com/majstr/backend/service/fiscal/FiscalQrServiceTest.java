package com.majstr.backend.service.fiscal;

import com.majstr.backend.config.FiscalQrProperties;
import com.sun.net.httpserver.HttpServer;
import com.majstr.backend.service.importer.EstimateExtractor.Extracted.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The degradation ladder, which is the whole design of this service: a fast path that is never the
 * only path. Every rung below is a failure the master must not feel as an error.
 */
class FiscalQrServiceTest {

    /** Lookup switched off — the same state as an unset FISCAL_QR_BASE_URL in a deploy. */
    private final FiscalQrService service = new FiscalQrService(new FiscalQrProperties(""));

    @Test
    void anUnreadablePayloadIsNotRecognizedAtAll() {
        // The one case where the caller should fall back to the photo, so it must be distinguishable
        // from "recognized, but no positions".
        assertThat(service.read("https://example.com/not-a-receipt")).isEmpty();
        assertThat(service.read(null)).isEmpty();
    }

    @Test
    void withoutTheLookupTheCodeItselfStillFillsTheDialog() {
        Optional<FiscalReceipt> read =
                service.read("fn=4000123456&id=17&date=20260815&time=143005&sm=1250.50");

        assertThat(read).isPresent();
        FiscalReceipt receipt = read.get();
        assertThat(receipt.total()).isEqualByComparingTo("1250.50");
        assertThat(receipt.issuedAt()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(receipt.label()).isNull();   // the seller's name only comes from the lookup
        assertThat(receipt.items()).isEmpty();  // and so do the positions
    }

    @Test
    void positionsSurviveOnlyWhenTheyAddUpToTheReceiptTotal() {
        List<Line> lines = List.of(
                line("Шпаклівка", "2", "345.00"),
                line("Грунтовка", "1", "210.00"));

        assertThat(FiscalQrService.trustedItems(lines, new BigDecimal("900.00"))).hasSize(2);
        // Within the rounding slack the decoder's scaling guess is still credible…
        assertThat(FiscalQrService.trustedItems(lines, new BigDecimal("900.01"))).hasSize(2);
        // …past it, the guess was wrong and no position is worth showing a master.
        assertThat(FiscalQrService.trustedItems(lines, new BigDecimal("90.00"))).isEmpty();
    }

    @Test
    void anIncompleteLineDropsTheWholeSet() {
        // A line with no quantity cannot be checked, so the set cannot be — and a review screen that
        // shows two trustworthy rows next to one unchecked one is worse than showing none.
        List<Line> lines = List.of(
                line("Шпаклівка", "2", "345.00"),
                line("Пакет", null, "5.00"));

        assertThat(FiscalQrService.trustedItems(lines, new BigDecimal("695.00"))).isEmpty();
    }

    @Test
    void noLinesIsNotAFailure() {
        assertThat(FiscalQrService.trustedItems(List.of(), BigDecimal.TEN)).isEmpty();
        assertThat(FiscalQrService.trustedItems(null, BigDecimal.TEN)).isEmpty();
    }

    private static Line line(String name, String qty, String price) {
        return new Line(name, "шт",
                qty == null ? null : new BigDecimal(qty),
                price == null ? null : new BigDecimal(price),
                "MATERIAL", null);
    }
/**
     * The lookup URI must reach the tax service EXACTLY as built.
     *
     * <p>It used to be handed to {@code RestClient.uri(String)}, which reads a String as a URI
     * TEMPLATE and encodes it a second time: the space in {@code date} arrived as {@code %2520}, the
     * tax service answered "request processing error", and the ladder degraded to meta-only. Every
     * scan filled the total and the date and never once produced a position — a silent failure that
     * looked exactly like "this receipt has no positions". This pins the wire format.
     */
    @Test
    void theLookupUriIsNotEncodedTwice() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> seenQuery = new AtomicReference<>();
        String checkXml = Base64.getEncoder().encodeToString("""
                <?xml version="1.0" encoding="windows-1251"?>
                <CHECK>
                  <CHECKBODY><ROW NAME="Сумка" AMOUNT="1000" PRICE="1000" COST="1000"/></CHECKBODY>
                  <CHECKTOTAL><SUM>1000</SUM></CHECKTOTAL>
                </CHECK>
                """.getBytes(Charset.forName("windows-1251")));
        server.createContext("/lookup", exchange -> {
            seenQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"checkXml\":\"" + checkXml + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort() + "/lookup";
            Optional<FiscalReceipt> read = new FiscalQrService(new FiscalQrProperties(base))
                    .read("fn=4000123456&id=17&date=20260821&time=1525&sm=10.00");

            String date = param(seenQuery.get(), "date");
            assertThat(date)
                    .as("one decode must yield the value itself, not another encoded form")
                    .isEqualTo("2026-08-21 15:25:00")
                    .doesNotContain("%");
            assertThat(read).isPresent();
            assertThat(read.get().items()).extracting(Line::name).containsExactly("Сумка");
        } finally {
            server.stop(0);
        }
    }

    /** The single-decoded value of one query parameter. */
    private static String param(String rawQuery, String name) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
