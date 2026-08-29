package com.majstr.backend.service.fiscal;

import com.majstr.backend.config.FiscalQrProperties;
import com.majstr.backend.config.HttpClients;
import com.majstr.backend.service.importer.EstimateExtractor.Extracted.Line;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fiscal-QR fast path: read a receipt from its printed QR code instead of from a photo.
 *
 * <p>Every Ukrainian fiscal receipt prints a QR carrying the cash register's fiscal number, the
 * receipt number, the timestamp and the total. That quartet alone is already most of what the
 * receipt dialog asks for — so <b>a scan is useful before any network call happens</b>. The lookup
 * on top of it adds the seller's name and the purchased positions, exactly as the tax service stored
 * them: data, not recognition.
 *
 * <p><b>This is a fast path, never the only path.</b> The endpoint is undocumented, its captcha
 * parameter is accepted empty only by today's behaviour, and it covers fiscal РРО/ПРРО receipts
 * only — a builders' merchant invoice or a handwritten slip has no QR at all. So every failure
 * degrades one step instead of surfacing:
 * <ol>
 *   <li>payload unreadable → nothing recognized, the caller falls back to photo recognition;</li>
 *   <li>lookup unreachable or refused → still recognized, with the total and date the QR itself
 *       carried and no positions;</li>
 *   <li>positions that do not add up to the QR's total → dropped wholesale, meta kept.</li>
 * </ol>
 *
 * <p>That last check is what makes the undocumented XML safe to trust: the decoder guesses at
 * integer scaling, and the QR's own total is an independent witness. If the sum of the lines is not
 * that number, the guess was wrong and no position is worth showing.
 */
@Slf4j
@Service
public class FiscalQrService {

    /** A public lookup that either answers immediately or is having a bad day; do not wait on it. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** Rounding slack when checking the lines against the receipt total. */
    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.02");

    private final FiscalQrProperties props;
    private final RestClient restClient = HttpClients.withTimeouts(READ_TIMEOUT);

    public FiscalQrService(FiscalQrProperties props) {
        this.props = props;
    }

    /**
     * Read a scanned QR payload into a receipt, positions included — the enriching lookup runs.
     */
    public Optional<FiscalReceipt> read(String payload) {
        return read(payload, true);
    }

    /**
     * Read a scanned QR payload into a receipt, or empty when the payload is not a fiscal code we
     * understand — the one case where the caller should fall back to reading the photo.
     *
     * <p>{@code withPositions=false} skips the tax-service lookup entirely and answers from the code
     * alone. That is not an optimisation detail: the lookup adds only the seller name and the
     * purchased lines, and since the receipts-batch iteration a receipt is named «Чек №N» by
     * default, so a caller that does not want positions has nothing to gain from it and everything
     * to lose — it is an undocumented third party with a 10 s timeout, tried automatically on every
     * photo of a batch. Without it this method makes no network call at all.</p>
     */
    public Optional<FiscalReceipt> read(String payload, boolean withPositions) {
        Optional<FiscalQrPayload> parsed = FiscalQrPayload.parse(payload);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        FiscalQrPayload qr = parsed.get();
        // What the code itself carries. Already enough to fill the dialog; the lookup only enriches.
        FiscalReceipt fromCode =
                new FiscalReceipt(null, qr.issuedAt().toLocalDate(), qr.sum(), List.of());

        if (!withPositions || !props.enabled()) {
            return Optional.of(fromCode);
        }
        FiscalReceipt looked = lookup(qr);
        if (looked == null) {
            return Optional.of(fromCode);
        }
        return Optional.of(new FiscalReceipt(
                looked.label(),
                // The QR's own values win: the lookup only answers at all when they matched it.
                qr.issuedAt().toLocalDate(),
                qr.sum(),
                trustedItems(looked.items(), qr.sum())));
    }

    // ---- the tax service's lookup ---------------------------------------------

    /** The stored receipt, or null on any failure — an enrichment is never worth an error. */
    private FiscalReceipt lookup(FiscalQrPayload qr) {
        try {
            // Built as a URI, never as a String: RestClient reads a String as a URI TEMPLATE and
            // encodes it a second time, so the space in `date` reaches the tax service as %2520 and
            // every lookup comes back "request processing error" - meta only, positions never.
            URI uri = UriComponentsBuilder.fromUriString(props.baseUrl())
                    .queryParam("id", qr.id())
                    .queryParam("date", qr.lookupDate())
                    .queryParam("type", "3")
                    .queryParam("captcha", "")
                    .queryParam("fn", qr.fn())
                    .queryParam("sm", qr.lookupSum())
                    .encode()
                    .build()
                    .toUri();

            Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
            if (body == null) return null;
            if (body.get("error") != null) {
                log.info("Fiscal lookup refused fn={} id={}: {}", qr.fn(), qr.id(),
                        body.get("error_description"));
                return null;
            }
            byte[] xml = decode(body.get("checkXml"));
            return xml == null ? null : FiscalCheckXml.parse(xml);
        } catch (Exception e) {
            // Undocumented upstream: a shape change, a new captcha or an outage all land here, and
            // all of them mean the same thing to the master — no positions, type the amount.
            log.info("Fiscal lookup failed for fn={} id={}: {}", qr.fn(), qr.id(), e.toString());
            return null;
        }
    }

    private static byte[] decode(Object base64) {
        if (!(base64 instanceof String s) || s.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The positions, but only if they add up to the receipt's total. The decoder has to guess how
     * the vendor scaled its integers; this is the independent check on that guess, and a wrong
     * guess must cost the master nothing but the positions.
     */
    // Package-private so the cross-check can be exercised directly: it is the whole reason the
    // undocumented XML is safe to show a master, and reaching it through read() would need a live
    // lookup.
    static List<Line> trustedItems(List<Line> items, BigDecimal total) {
        if (items == null || items.isEmpty()) return List.of();
        BigDecimal sum = BigDecimal.ZERO;
        for (Line line : items) {
            if (line.quantity() == null || line.unitPrice() == null) {
                // An incomplete line can't be checked, so the set can't be — keep the review honest.
                return List.of();
            }
            sum = sum.add(line.quantity().multiply(line.unitPrice()));
        }
        if (sum.subtract(total).abs().compareTo(SUM_TOLERANCE) > 0) {
            log.info("Fiscal lines dropped: they sum to {} but the receipt total is {}", sum, total);
            return List.of();
        }
        return items;
    }
}
