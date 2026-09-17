package com.majstr.backend.service.fiscal;

import com.majstr.backend.dto.ReceiptRecognizeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Read a receipt's printed QR for a receipt dialog — the ONE implementation both receipt tables use
 * (review item B-04).
 *
 * <p>It exists because the two callers had drifted in the way that quietly kills a feature. The
 * object's «Чеки» returned the fiscal identity off the payload; the act's «Чеки та рахунки» ran the
 * same lookup and threw the identity away. So an act receipt could never carry {@code fn}/{@code id}
 * no matter what V134 added to its table, and the cross-table duplicate check would have had nothing
 * to compare on one whole side of the pair. One method, both callers, no way to drift again.</p>
 *
 * <p><b>The identity comes from the PAYLOAD, never from what the ДПС lookup answered.</b> The ladder
 * in {@link FiscalQrService#read} degrades rather than fails — an unreachable tax service still
 * yields a recognized receipt off total + date — and the identity is printed on the paper either
 * way. Reading it from the lookup would make it vanish on exactly the bad day the master is standing
 * in a basement.</p>
 *
 * <p>The lookup is asked for WITHOUT positions: neither surface carries a receipt's line items any
 * more, so a purely local read is instant and independent of a third party's latency — which is what
 * makes it safe to fire automatically on every photo of a batch.</p>
 */
@Component
@RequiredArgsConstructor
public class FiscalQrReceiptReader {

    private final FiscalQrService fiscalQr;

    /** A soft outcome either way: an unreadable payload answers {@code recognized=false}. */
    public ReceiptRecognizeResponse read(String payload) {
        return fiscalQr.read(payload, false)
                .map(r -> {
                    Optional<FiscalQrPayload> qr = FiscalQrPayload.parse(payload);
                    return ReceiptRecognizeResponse.identified(r.label(), r.total(), r.issuedAt(),
                            qr.map(FiscalQrPayload::fn).orElse(null),
                            qr.map(FiscalQrPayload::id).orElse(null));
                })
                .orElseGet(ReceiptRecognizeResponse::failed);
    }
}
