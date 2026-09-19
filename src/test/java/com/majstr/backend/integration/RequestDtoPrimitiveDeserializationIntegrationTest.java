package com.majstr.backend.integration;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field the client simply LEFT OUT may never be a 400.
 *
 * <p><b>What broke.</b> Jackson 3 turned {@code FAIL_ON_NULL_FOR_PRIMITIVES} on by default;
 * Jackson 2 shipped it off. A record's canonical constructor is handed {@code null} for an absent
 * property, so from the moment V135 added {@code boolean materialRefund} to the two receipt
 * requests, every «отримано» the object economy sent — a payload that never carried the flag —
 * died in the message converter as {@code HttpMessageNotReadableException} and came back as
 * {@code error.malformed-json}, «Некоректний формат запиту». Receiving money on an object had
 * been impossible since that commit; planning a stage (no primitive on its request) still worked,
 * which is exactly why it read as one screen misbehaving rather than a mapper setting.</p>
 *
 * <p><b>Why an integration test.</b> The fix is a Boot property
 * ({@code spring.jackson.deserialization.fail-on-null-for-primitives}), so only the CONTEXT's
 * mapper proves it. A standalone MockMvc controller test builds its own default converter and
 * would answer about Jackson's defaults instead of about ours — it would stay green if the
 * property were deleted tomorrow.</p>
 *
 * <p><b>Why it scans the whole package.</b> The bug is not about one flag. Nine request records
 * carry a primitive today, every one of them a live 400 waiting for the first caller that omits
 * it, and the next one gets added by someone who has no reason to know any of this. The sweep
 * fails on the class it found, so a new DTO is caught by a test nobody had to remember to
 * update.</p>
 */
class RequestDtoPrimitiveDeserializationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyRequestRecordAcceptsAPayloadThatOmitsItsPrimitives() {
        List<String> broken = new ArrayList<>();
        List<String> swept = new ArrayList<>();

        for (Class<?> dto : dtoRecords()) {
            if (!hasPrimitiveComponent(dto)) {
                continue;
            }
            swept.add(dto.getSimpleName());
            try {
                objectMapper.readValue("{}", dto);
            } catch (MismatchedInputException ex) {
                // Only the primitive complaint matters here. A DTO refusing {} for any other
                // reason (a custom deserializer, a polymorphic base) is not this bug.
                if (String.valueOf(ex.getMessage()).contains("Cannot map `null` into type")) {
                    broken.add(dto.getSimpleName());
                }
            } catch (RuntimeException ignored) {
                // Same: not a null-for-primitive rejection, not this test's business.
            }
        }

        // A sweep that finds nothing passes forever and guards nothing — the exact way the update
        // banner stayed broken for a year with green tests. Prove it still reaches the record this
        // bug was reported on before believing the empty result. (Its edit sibling is deliberately
        // absent: that one's flag is a three-valued Boolean now, so it carries no primitive.)
        assertThat(swept)
                .as("records the scan actually reached")
                .contains("PaymentReceiptRequest")
                .hasSizeGreaterThanOrEqualTo(5);

        assertThat(broken)
                .as("request records that answer 400 when the client omits a primitive field")
                .isEmpty();
    }

    /** The exact body the object economy's «отримано» sheet sends — no {@code materialRefund}. */
    @Test
    void theEconomyReceiptPayloadParsesAndDefaultsTheRefundFlagToFalse() {
        PaymentReceiptRequest req = objectMapper.readValue("""
                {"planPaymentId":null,"label":"Zavdatok","amount":5000,
                 "receivedAt":"2026-09-19","resolution":null}
                """, PaymentReceiptRequest.class);

        assertThat(req.amount()).isEqualByComparingTo("5000");
        assertThat(req.materialRefund()).isFalse();
    }

    /** The edit sheet's body. {@code materialRefund} is three-valued here — absent must stay
     *  null, or editing an amount from the object screen clears a refund flag «Мої гроші» set. */
    @Test
    void theEconomyReceiptEditPayloadLeavesTheRefundFlagUntouched() {
        PaymentReceiptEditRequest req = objectMapper.readValue(
                """
                {"amount":5000,"receivedAt":"2026-09-19","label":null}
                """, PaymentReceiptEditRequest.class);

        assertThat(req.materialRefund()).isNull();
    }

    private static List<Class<?>> dtoRecords() {
        // Records carry no stereotype annotation, so the scan takes everything and filters after.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        TypeFilter everything = (metadataReader, factory) -> true;
        scanner.addIncludeFilter(everything);

        List<Class<?>> records = new ArrayList<>();
        scanner.findCandidateComponents("com.majstr.backend.dto").forEach(bd -> {
            try {
                Class<?> type = Class.forName(bd.getBeanClassName());
                if (type.isRecord()) {
                    records.add(type);
                }
            } catch (ClassNotFoundException ignored) {
                // Nothing on this classpath can vanish between the scan and the load.
            }
        });
        return records;
    }

    private static boolean hasPrimitiveComponent(Class<?> record) {
        for (RecordComponent component : record.getRecordComponents()) {
            if (component.getType().isPrimitive()) {
                return true;
            }
        }
        return false;
    }
}
