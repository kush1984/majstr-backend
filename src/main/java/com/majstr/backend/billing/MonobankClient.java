package com.majstr.backend.billing;

import com.majstr.backend.config.BillingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin HTTP client for the monobank acquiring API
 * (https://api.monobank.ua/api/merchant/...). Auth is the merchant token in the
 * {@code X-Token} header (env only, via {@link BillingProperties}). Kept small and
 * isolated so the exact wire shape lives in one place — confirm field names
 * against monobank's live docs / sandbox when wiring the real token.
 */
@Slf4j
@Component
public class MonobankClient {

    private final BillingProperties props;
    private final RestClient restClient = RestClient.create();

    /** Cached merchant public key (used to verify webhook signatures). */
    private volatile String cachedPublicKey;

    public MonobankClient(BillingProperties props) {
        this.props = props;
    }

    /** Creates a hosted-page invoice; returns the gateway invoice id + the page URL to redirect the payer to. */
    @SuppressWarnings("unchecked")
    public InvoiceCreated createInvoice(long amountKopiykas, int ccy, String reference, String destination) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountKopiykas);
        body.put("ccy", ccy);
        body.put("merchantPaymInfo", Map.of("reference", reference, "destination", destination));
        body.put("redirectUrl", props.returnUrl());
        body.put("webHookUrl", props.webhookUrl());
        body.put("validity", 3600); // seconds the payment page stays valid
        body.put("paymentType", "debit");

        Map<String, Object> resp = restClient.post()
                .uri(props.monobankApiBase() + "/api/merchant/invoice/create")
                .header("X-Token", props.monobankToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (resp == null || resp.get("invoiceId") == null || resp.get("pageUrl") == null) {
            throw new IllegalStateException("monobank invoice/create returned no invoiceId/pageUrl");
        }
        return new InvoiceCreated((String) resp.get("invoiceId"), (String) resp.get("pageUrl"));
    }

    /**
     * Merchant public key for verifying webhook {@code X-Sign} signatures. Cached
     * after the first call (the key is stable); refresh by restart if monobank
     * ever rotates it.
     */
    @SuppressWarnings("unchecked")
    public String publicKey() {
        String cached = cachedPublicKey;
        if (cached != null) {
            return cached;
        }
        Map<String, Object> resp = restClient.get()
                .uri(props.monobankApiBase() + "/api/merchant/pubkey")
                .header("X-Token", props.monobankToken())
                .retrieve()
                .body(Map.class);
        if (resp == null || resp.get("key") == null) {
            throw new IllegalStateException("monobank pubkey returned no key");
        }
        String key = (String) resp.get("key");
        cachedPublicKey = key;
        return key;
    }

    public record InvoiceCreated(String invoiceId, String pageUrl) {}
}
