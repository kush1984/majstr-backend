package com.majstr.backend.service;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiFlow;
import com.majstr.backend.service.ai.AiInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a client's bank receipt («Я оплатив», V217) on {@link AiFlow#PAYMENT_RECEIPT}: the transfer
 * amount, who received it, when, and whether the bank says it went through. It only READS — whether
 * that matches the notice is {@link PaymentReceiptCheck}'s decision, in code, never the model's.
 *
 * <p>A bank app's PDF usually carries its text: that text is sent instead of the file, which is
 * cheaper and reads digits exactly. A scanned PDF goes whole; a photo or screenshot as an image.</p>
 *
 * <p>Throws like every extractor; the caller turns any failure into «не вдалося прочитати».</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReceiptReader {

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");
    /** Less than this is a scan with a stray text layer, not the receipt's text. */
    private static final int MIN_PDF_TEXT = 40;
    private static final int MAX_PDF_TEXT = 6000;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yy"));

    private final AiExtractors extractors;
    private final ObjectMapper objectMapper;

    /** What the bank printed. Any field may be null: unreadable, or simply not on the receipt. */
    public record Read(BigDecimal amount, String currency, Status status, LocalDate date,
                       String recipientIban, String recipientCardLast4, String recipientName) {}

    public enum Status { SUCCESS, IN_PROGRESS, FAILED, UNKNOWN }

    public Read read(String contentType, byte[] bytes) {
        List<AiInput> input;
        if ("application/pdf".equals(contentType)) {
            String text = pdfText(bytes);
            input = text != null
                    ? AiInput.text("The receipt's text, extracted from its PDF:\n\n" + text)
                    : AiInput.pdf(bytes, INSTRUCTION);
        } else if (contentType != null && contentType.startsWith("image/")) {
            input = AiInput.image(contentType, bytes, INSTRUCTION);
        } else {
            throw new AiExtractionException("error.ai.unavailable");
        }
        return parse(extractors.forFlow(AiFlow.PAYMENT_RECEIPT).requestJson(input, PROMPT, schema()));
    }

    private static String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc).strip();
            if (text.length() < MIN_PDF_TEXT) {
                return null;
            }
            return text.length() > MAX_PDF_TEXT ? text.substring(0, MAX_PDF_TEXT) : text;
        } catch (IOException e) {
            return null; // not a readable PDF: let the model look at it
        }
    }

    @SuppressWarnings("unchecked")
    Read parse(String json) { // package-private for the unit test
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            BigDecimal amount = bd(root.get("amount"));
            if (amount != null && amount.signum() <= 0) {
                amount = null; // 0 = the prompt's "unreadable"
            }
            String iban = str(root.get("recipientIban"));
            if (iban != null) {
                iban = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            }
            String last4 = str(root.get("recipientCardLast4"));
            if (last4 != null && !last4.matches("\\d{4}")) {
                last4 = null;
            }
            return new Read(amount, str(root.get("currency")), status(str(root.get("status"))),
                    date(str(root.get("date"))), iban, last4, str(root.get("recipientName")));
        } catch (Exception e) {
            log.warn("Payment receipt JSON unusable: {}", e.getMessage());
            throw new AiExtractionException("error.ai.unavailable", e);
        }
    }

    private static Status status(String raw) {
        if (raw == null) {
            return Status.UNKNOWN;
        }
        try {
            return Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Status.UNKNOWN;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw.trim(), format);
            } catch (DateTimeParseException ignored) {
                // next shape
            }
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().replace(',', '.').replace(" ", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String INSTRUCTION = "Read this bank transfer receipt.";

    private static final String PROMPT = """
            You read a Ukrainian bank's receipt for a money transfer (квитанція / платіжна інструкція)
            from any bank app — Приват24, monobank, Ощад, ПУМБ, Райффайзен, А-Банк, Sense and others —
            as a screenshot, a photo or the text of its PDF. Return:
              - amount: the TRANSFER amount the recipient gets, as a number with kopecks (10000.00).
                NOT the commission («комісія»), and NOT the total with the commission added
                («до списання», «всього з комісією») when the transfer amount is printed separately.
                0 if unreadable.
              - currency: "UAH", "USD", "EUR" … "" if not printed.
              - status: SUCCESS when the receipt says it went through («Успішно», «Виконано»,
                «Проведено», «Зараховано»); IN_PROGRESS for «В обробці», «Очікує», «Обробляється»;
                FAILED for «Відхилено», «Помилка», «Скасовано»; UNKNOWN when nothing says.
              - date: the transfer's date as ISO "YYYY-MM-DD" (receipts print DD.MM.YYYY). "" if none.
              - recipientIban: the RECIPIENT's IBAN (UA + 27 digits), exactly as printed, when it is
                printed in full. Never the sender's. "" if absent or masked.
              - recipientCardLast4: the LAST FOUR digits of the RECIPIENT's card, which banks print
                masked like «5168 **** **** 1234» or «*1234». Never the sender's card. "" if absent.
              - recipientName: the recipient («Отримувач») as printed, often shortened. "" if absent.

            A receipt names a sender and a recipient: read only the recipient's details. Use the
            sentinels ("" / 0 / UNKNOWN) for anything you cannot read confidently — a wrong figure is
            worse than an empty one.""";

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("amount", NUMBER);
        properties.put("currency", STRING);
        properties.put("status", Map.of("type", "string",
                "enum", List.of("SUCCESS", "IN_PROGRESS", "FAILED", "UNKNOWN")));
        properties.put("date", STRING);
        properties.put("recipientIban", STRING);
        properties.put("recipientCardLast4", STRING);
        properties.put("recipientName", STRING);
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.copyOf(properties.keySet()),
                "properties", properties);
    }
}
