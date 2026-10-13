package com.majstr.backend.service;

import com.majstr.backend.entity.ClientType;
import com.majstr.backend.entity.EstimateKind;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActLineKind;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every value a signed document PRINTS, as it stood at the signature (V150, review B-76), and the
 * one canonical text form its {@code doc_hash} is computed over.
 *
 * <p>What is in here is what the PDF, the act portal and the estimate portal read off the live
 * entities — the master's requisites and logo, the client, the object, the lines, the receipts,
 * the «ДОВІДКОВО» figures. What is NOT: the signer's IP and user agent (personal data, never
 * printed) and anything only the master's own screens show.</p>
 *
 * <p><b>The canonical form is fixed by this class, not by the application's mapper</b>: sorted
 * properties and map keys, decimals as written with their scale, dates as ISO text, no
 * whitespace. A change here changes the text of documents signed AFTER it, never the stored text of
 * one signed before — that is kept verbatim — so a structural change bumps {@link #SCHEMA}.</p>
 */
public final class SignedSnapshot {

    public static final int SCHEMA = 1;

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private SignedSnapshot() {
    }

    /** The master as the document names him. {@code branded} is the logo decision of the day. */
    public record Contractor(String fullName, String companyName, String phone, String legalName,
                             String taxId, String legalAddress, String iban, String bankName,
                             boolean vatPayer, String vatId, Short taxGroup, BigDecimal taxRate,
                             String docCity, boolean branded, String logoSha256) { }

    public record ProjectPart(UUID id, String name, String address) { }

    public record ClientPart(String fullName, String phone, String email, ClientType clientType,
                             String taxId, String legalName, String legalAddress,
                             String signatoryTitle, String signatoryName) { }

    public record Signer(Instant signedAt, String name, String phone, boolean offline) { }

    public record ActPart(UUID id, String number, String title, WorkActKind kind, LocalDate issuedAt,
                          LocalDate periodFrom, LocalDate periodTo, String place, String contractRef,
                          BigDecimal advanceOffset, boolean showMaterials, boolean showCumulative,
                          boolean showReceiptPhotos) { }

    public record ActLine(UUID id, WorkActLineKind lineKind, UUID estimateItemId, UUID estimateId,
                          ItemType type, String name, String category, Unit unit, BigDecimal unitPrice,
                          BigDecimal quantity, BigDecimal lineTotal, BigDecimal cumulativeBefore,
                          int sortOrder) { }

    public record ActReceipt(UUID id, String label, LocalDate issuedAt, BigDecimal amount,
                             BigDecimal returnedAmount, String storageKey, boolean itemized) { }

    /** The «ДОВІДКОВО» figures as of the signature (B-94); null when the act printed no block. */
    public record Cumulative(BigDecimal accepted, BigDecimal contracted) { }

    public record ActDoc(int schema, Contractor contractor, ProjectPart project, ClientPart client,
                         ActPart act, Signer signer, List<ActLine> lines, List<ActReceipt> receipts,
                         Map<UUID, String> estimateNames, Cumulative cumulative) { }

    public record EstimatePart(UUID id, String name, EstimateKind kind, LocalDate validUntil,
                               String notes, BigDecimal depositAmount, Instant createdAt) { }

    public record EstimateLine(UUID id, ItemType type, String name, String category, Trade trade,
                               Unit unit, BigDecimal quantity, BigDecimal unitPrice,
                               BigDecimal lineTotal, PercentBaseKind percentBaseKind,
                               UUID percentBaseItemId, String baseOriginLabel, int sortOrder) { }

    public record EstimateDoc(int schema, Contractor contractor, ProjectPart project, ClientPart client,
                              EstimatePart estimate, Signer signer, List<EstimateLine> lines) { }

    /** The canonical text — the exact bytes {@link #sha256} certifies. */
    public static String canonical(Object snapshot) {
        return CANONICAL.writeValueAsString(snapshot);
    }

    public static <T> T read(String canonical, Class<T> type) {
        return CANONICAL.readValue(canonical, type);
    }

    /** Lower-case hex SHA-256 of the UTF-8 text. */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Lower-case hex SHA-256 of raw bytes — also the logo's fingerprint inside the snapshot. */
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
