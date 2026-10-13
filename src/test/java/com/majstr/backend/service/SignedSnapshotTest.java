package com.majstr.backend.service;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActLineKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical text a {@code doc_hash} is computed over (B-76). It has to be a FUNCTION of the
 * values: the same document must hash the same however its parts were assembled, and a printed
 * figure must keep the scale it was printed with.
 */
class SignedSnapshotTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void theSameDocumentIsTheSameText_whateverOrderItsMapWasFilledIn() {
        Map<UUID, String> ab = new LinkedHashMap<>();
        ab.put(A, "Санвузол");
        ab.put(B, "Кухня");
        Map<UUID, String> ba = new LinkedHashMap<>();
        ba.put(B, "Кухня");
        ba.put(A, "Санвузол");

        assertThat(SignedSnapshot.canonical(doc(ab))).isEqualTo(SignedSnapshot.canonical(doc(ba)));
        assertThat(SignedSnapshot.sha256(SignedSnapshot.canonical(doc(ab))))
                .isEqualTo(SignedSnapshot.sha256(SignedSnapshot.canonical(doc(ba))))
                .hasSize(64);
    }

    @Test
    void decimalsKeepTheirScale_andDatesAreText() {
        String text = SignedSnapshot.canonical(doc(Map.of()));
        assertThat(text).contains("\"lineTotal\":1450.00", "\"quantity\":10.000",
                "\"issuedAt\":\"2026-10-01\"", "\"signedAt\":\"2026-10-12T08:00:00Z\"");
        assertThat(text).doesNotContain("1.45E", "\n", ": ");
    }

    @Test
    void propertiesAreSorted() {
        String text = SignedSnapshot.canonical(new SignedSnapshot.ProjectPart(A, "Обʼєкт", "Адреса"));
        assertThat(text).isEqualTo("{\"address\":\"Адреса\",\"id\":\"" + A + "\",\"name\":\"Обʼєкт\"}");
    }

    @Test
    void readingBackAndWritingAgainGivesTheSameText() {
        String text = SignedSnapshot.canonical(doc(Map.of(A, "Санвузол")));
        assertThat(SignedSnapshot.canonical(SignedSnapshot.read(text, SignedSnapshot.ActDoc.class))).isEqualTo(text);
    }

    @Test
    void aChangedFigureIsAChangedHash() {
        String one = SignedSnapshot.canonical(doc(Map.of()));
        String other = one.replace("1450.00", "1450.01");
        assertThat(SignedSnapshot.sha256(one)).isNotEqualTo(SignedSnapshot.sha256(other));
    }

    private static SignedSnapshot.ActDoc doc(Map<UUID, String> names) {
        return new SignedSnapshot.ActDoc(
                SignedSnapshot.SCHEMA,
                new SignedSnapshot.Contractor("Майстер", "ФОП", "+380", null, null, null,
                        "UA213223130000026007233566001", null, false, null, null, null, null, false, null),
                new SignedSnapshot.ProjectPart(A, "Обʼєкт", "Адреса"),
                null,
                new SignedSnapshot.ActPart(B, "1", null, WorkActKind.INTERIM, LocalDate.of(2026, 10, 1),
                        null, null, null, null, null, true, true, true),
                new SignedSnapshot.Signer(Instant.parse("2026-10-12T08:00:00Z"), "Клієнт", null, true),
                List.of(new SignedSnapshot.ActLine(A, WorkActLineKind.ESTIMATE, A, B, ItemType.WORK,
                        "Шпаклювання", null, Unit.M2, new BigDecimal("145.00"), new BigDecimal("10.000"),
                        new BigDecimal("1450.00"), null, 0)),
                List.of(),
                names,
                null);
    }
}
