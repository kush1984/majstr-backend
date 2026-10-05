package com.majstr.backend.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request contract the PWA codes against, frozen as a file.
 *
 * <p><b>The bug this exists for.</b> V135 added {@code boolean materialRefund} to
 * {@code PaymentReceiptRequest}. Nothing told the other repository, whose {@code src/api/types.ts}
 * is written BY HAND: its interface never grew the field, TypeScript stayed perfectly happy, and
 * the master could not record a payment for weeks. Neither repo's tests could see it — the
 * backend's build the request records in Java (so Jackson never runs), the PWA's mock the api
 * module (so the body is never serialized), and nothing at all read both sides.</p>
 *
 * <p><b>What this does about it.</b> Every {@code *Request} record's SHAPE — field name, coarse
 * type, and whether the client must send it — is written to a committed snapshot. Change a request
 * DTO and this test goes red in the same commit that changed it, naming the PWA file that has to
 * move with it. The snapshot is copied into the PWA, where {@code src/api/contract.test.ts} checks
 * the hand-written interfaces against it.</p>
 *
 * <p><b>Coarse types on purpose.</b> The far side is TypeScript, which cannot tell a {@code UUID}
 * from a {@code String} or a {@code BigDecimal} from an {@code int}. Recording Java's exact types
 * would churn the snapshot on changes the PWA cannot observe, and a guard that cries wolf gets
 * deleted. What matters there is: does the field exist, is it a string/number/boolean, and may it
 * be left out.</p>
 *
 * <p>Pure reflection, no Spring context — this must be the cheapest test in the suite so nobody is
 * ever tempted to skip it.</p>
 */
class RequestContractSnapshotTest {

    /** Committed, and copied verbatim into the PWA. Regenerate with {@code -Dcontract.update=true}. */
    private static final Path SNAPSHOT = Path.of("src/test/resources/contract/request-dtos.json");

    private static final String STALE = """
            A request DTO changed, so the contract the PWA codes against changed too.

            The PWA's src/api/types.ts is written BY HAND and no compiler connects the two
            repositories — this is the only moment anything will tell you. Do BOTH:
              1. ./gradlew test --tests '*RequestContractSnapshotTest' -Dcontract.update=true
              2. copy the snapshot to majstr-pwa/src/api/contract/request-dtos.json and make
                 src/api/types.ts agree; its contract.test.ts pins the rest.""";

    @Test
    void theRequestContractMatchesTheCommittedSnapshot() throws IOException {
        Map<String, Map<String, String>> contract = scanRequestRecords();

        // A sweep that finds nothing would freeze an empty contract and guard forever against
        // nothing at all — the exact way the PWA's update banner stayed broken for a year.
        assertThat(contract)
                .as("request records the scan reached")
                .containsKey("PaymentReceiptRequest")
                .hasSizeGreaterThan(50);

        String actual = render(contract);
        if (Boolean.getBoolean("contract.update")) {
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
        }

        assertThat(Files.exists(SNAPSHOT)).as(STALE).isTrue();
        // Line endings normalised on BOTH sides (review B-36): render() builds LF, and with
        // core.autocrlf a fresh Windows checkout hands back CRLF, so this assertion could
        // never hold — the suite was green only on the machine whose own run rewrote the file.
        // .gitattributes now pins the snapshot to LF; this keeps an older working copy honest too.
        assertThat(lf(actual)).as(STALE)
                .isEqualTo(lf(Files.readString(SNAPSHOT, StandardCharsets.UTF_8)));
    }

    /** name -&gt; (field -&gt; {@code "type"}, or {@code "type?"} when the client may leave it out). */
    private static Map<String, Map<String, String>> scanRequestRecords() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        TypeFilter everything = (metadataReader, factory) -> true;
        scanner.addIncludeFilter(everything);

        Map<String, Map<String, String>> contract = new TreeMap<>();
        scanner.findCandidateComponents("com.majstr.backend.dto").forEach(bd -> {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned class vanished: " + bd.getBeanClassName(), e);
            }
            if (!type.isRecord() || !type.getSimpleName().endsWith("Request")) {
                return;
            }
            record(contract, type.getSimpleName(), type);
        });
        return contract;
    }

    /**
     * One record's own shape, plus every NESTED record reachable from it (review B-46).
     *
     * <p>A component whose type is a record — or a {@code List<Entry>} of one — rendered as a bare
     * {@code "array"} or {@code "object"}, so the shape the client actually has to build was
     * outside the contract entirely. {@code ApplyTemplatesRequest.TemplatePick},
     * {@code EstimateItemFromCatalogBatchRequest.Entry}, the import commit items, the measurement
     * rooms and sheets: a required field added to any of them is the V135 bug one level down, and
     * nothing on either side would have gone red.</p>
     *
     * <p>Keyed {@code Parent.Nested}, which keeps the file's shape exactly as it was — a flat map of
     * name → field map — so the PWA's reader needs no new vocabulary to keep working. The nested
     * entries are additions, and a nested record used by two parents is recorded under each, since
     * that is the only way the file says who has to build it.</p>
     */
    private static void record(Map<String, Map<String, String>> contract, String name, Class<?> type) {
        if (contract.containsKey(name)) {
            return; // already recorded under this exact path
        }
        Map<String, String> fields = new TreeMap<>();
        contract.put(name, fields); // before the recursion: a self-referencing record must terminate
        for (RecordComponent c : type.getRecordComponents()) {
            fields.put(c.getName(), describe(c.getType()) + (required(type, c) ? "" : "?"));
            Class<?> nested = nestedRecord(c);
            if (nested != null) {
                record(contract, name + "." + nested.getSimpleName(), nested);
            }
        }
    }

    /** The record behind a component — itself, or the element type of its collection. */
    private static Class<?> nestedRecord(RecordComponent c) {
        if (c.getType().isRecord()) {
            return c.getType();
        }
        if (Collection.class.isAssignableFrom(c.getType())
                && c.getGenericType() instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> element && element.isRecord()) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * "Required" means the CLIENT must send it, which is the only sense the PWA can act on. A
     * primitive is never required: an omitted one takes its Java default (see
     * {@code fail-on-null-for-primitives}, the other half of this same bug).
     *
     * <p>Three places are searched, and the FIRST draft of this test looked only at the record
     * component — which reported all 80 DTOs as fully optional. {@code @NotNull} does not list
     * {@code RECORD_COMPONENT} among its targets, so javac propagates it to the backing field and
     * the canonical constructor's parameter and leaves the component bare. A contract claiming
     * nothing is required would have been worse than none: it would have gone green forever.</p>
     */
    private static boolean required(Class<?> record, RecordComponent c) {
        if (marksRequired(c.getAnnotations()) || marksRequired(c.getAnnotatedType().getAnnotations())) {
            return true;
        }
        try {
            if (marksRequired(record.getDeclaredField(c.getName()).getAnnotations())) {
                return true;
            }
        } catch (NoSuchFieldException ignored) {
            // A record always has one; nothing to do if a future compiler disagrees.
        }
        for (Constructor<?> ctor : record.getDeclaredConstructors()) {
            Parameter[] params = ctor.getParameters();
            RecordComponent[] components = record.getRecordComponents();
            if (params.length != components.length) {
                continue;
            }
            for (int i = 0; i < params.length; i++) {
                if (components[i].getName().equals(c.getName()) && marksRequired(params[i].getAnnotations())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean marksRequired(Annotation[] annotations) {
        for (Annotation a : annotations) {
            Class<?> t = a.annotationType();
            if (t == NotNull.class || t == NotBlank.class || t == NotEmpty.class) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Class<?> t) {
        if (t == boolean.class || t == Boolean.class) {
            return "boolean";
        }
        if (t.isPrimitive() || Number.class.isAssignableFrom(t)) {
            return "number";
        }
        // UUID, LocalDate, LocalDateTime, Instant and every enum cross the wire as JSON strings.
        if (t == String.class || t.isEnum() || t == UUID.class || t.getName().startsWith("java.time.")) {
            return "string";
        }
        if (Collection.class.isAssignableFrom(t) || t.isArray()) {
            return "array";
        }
        return "object";
    }

    /** Hand-rolled so the file's formatting is owned here and cannot shift under a Jackson upgrade. */
    private static String render(Map<String, Map<String, String>> contract) {
        StringBuilder out = new StringBuilder("{\n");
        int left = contract.size();
        for (Map.Entry<String, Map<String, String>> dto : contract.entrySet()) {
            out.append("  \"").append(dto.getKey()).append("\": {\n");
            int fieldsLeft = dto.getValue().size();
            for (Map.Entry<String, String> field : dto.getValue().entrySet()) {
                out.append("    \"").append(field.getKey()).append("\": \"").append(field.getValue())
                        .append(fieldsLeft-- > 1 ? "\",\n" : "\"\n");
            }
            out.append(left-- > 1 ? "  },\n" : "  }\n");
        }
        return out.append("}\n").toString();
    }

    /** CR/LF-insensitive comparison — see B-36 above. */
    private static String lf(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}
