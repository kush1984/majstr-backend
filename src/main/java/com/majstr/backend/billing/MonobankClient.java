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

    /**
     * Creates a hosted-page invoice; returns the gateway invoice id + the page URL
     * to redirect the payer to. When {@code saveToWalletId} is non-null the card is
     * tokenized on payment (saveCardData) into that wallet, so it can later be
     * charged merchant-initiated for auto-renewal.
     */
    @SuppressWarnings("unchecked")
    public InvoiceCreated createInvoice(long amountKopiykas, int ccy, String reference,
                                        String destination, String saveToWalletId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountKopiykas);
        body.put("ccy", ccy);
        body.put("merchantPaymInfo", Map.of("reference", reference, "destination", destination));
        body.put("redirectUrl", props.returnUrl());
        body.put("webHookUrl", props.webhookUrl());
        body.put("validity", 3600); // seconds the payment page stays valid
        body.put("paymentType", "debit");
        if (saveToWalletId != null && !saveToWalletId.isBlank()) {
            body.put("saveCardData", Map.of("saveCard", true, "walletId", saveToWalletId));
        }

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
     * The first tokenized card in a wallet (cardToken + masked PAN), or null if none
     * yet. Called after a tokenizing checkout succeeds to persist the token.
     * {@code GET /api/merchant/wallet?walletId=...}.
     */
    @SuppressWarnings("unchecked")
    public WalletCard firstWalletCard(String walletId) {
        Map<String, Object> resp = restClient.get()
                .uri(props.monobankApiBase() + "/api/merchant/wallet?walletId=" + walletId)
                .header("X-Token", props.monobankToken())
                .retrieve()
                .body(Map.class);
        if (resp == null) {
            return null;
        }
        Object wallet = resp.get("wallet");
        if (!(wallet instanceof java.util.List<?> cards) || cards.isEmpty()) {
            return null;
        }
        Map<String, Object> card = (Map<String, Object>) cards.get(0);
        String token = (String) card.get("cardToken");
        if (token == null) {
            return null;
        }
        return new WalletCard(token, (String) card.get("maskedPan"));
    }

    /**
     * Merchant-initiated charge of a saved card token (auto-renewal). Returns the
     * gateway invoice id; the actual result arrives via the SAME invoice-status
     * webhook (so idempotency reuses {@code payments.invoice_id}).
     * {@code POST /api/merchant/wallet/payment}, {@code initiationKind=merchant}.
     */
    @SuppressWarnings("unchecked")
    public String tokenPayment(String cardToken, long amountKopiykas, int ccy,
                               String reference, String destination) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cardToken", cardToken);
        body.put("amount", amountKopiykas);
        body.put("ccy", ccy);
        body.put("initiationKind", "merchant"); // recurring, merchant-initiated
        body.put("webHookUrl", props.webhookUrl());
        body.put("paymentType", "debit");
        body.put("merchantPaymInfo", Map.of("reference", reference, "destination", destination));

        Map<String, Object> resp = restClient.post()
                .uri(props.monobankApiBase() + "/api/merchant/wallet/payment")
                .header("X-Token", props.monobankToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (resp == null || resp.get("invoiceId") == null) {
            throw new IllegalStateException("monobank wallet/payment returned no invoiceId");
        }
        return (String) resp.get("invoiceId");
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

    /** A tokenized card: the token (sensitive) + masked PAN (display-safe). */
    public record WalletCard(String cardToken, String maskedPan) {}
}
