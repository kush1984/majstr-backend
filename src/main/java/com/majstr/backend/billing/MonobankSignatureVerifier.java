package com.majstr.backend.billing;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies the monobank webhook {@code X-Sign} header: a base64 ECDSA
 * (SHA256withECDSA) signature over the <b>raw request body bytes</b>, checked
 * against the merchant public key from {@code /api/merchant/pubkey}. The pubkey
 * arrives base64-encoded and, once decoded, is a PEM ({@code -----BEGIN PUBLIC
 * KEY-----}) wrapping the X.509/DER key.
 *
 * <p>This is the security gate for granting PRO: an unsigned or forged webhook
 * must never flip a payment to paid. BouncyCastle (already on the classpath via
 * web-push) is registered so any EC curve parses.</p>
 */
@Slf4j
@Component
public class MonobankSignatureVerifier {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /** @return true only if {@code xSignBase64} is a valid signature of {@code body} under {@code pubKeyBase64}. */
    public boolean verify(byte[] body, String xSignBase64, String pubKeyBase64) {
        if (body == null || xSignBase64 == null || xSignBase64.isBlank()
                || pubKeyBase64 == null || pubKeyBase64.isBlank()) {
            return false;
        }
        try {
            PublicKey publicKey = parsePublicKey(pubKeyBase64);
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initVerify(publicKey);
            ecdsa.update(body);
            return ecdsa.verify(Base64.getDecoder().decode(xSignBase64));
        } catch (Exception e) {
            log.warn("monobank webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static PublicKey parsePublicKey(String pubKeyBase64) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(pubKeyBase64);
        String asText = new String(decoded).trim();
        byte[] der;
        if (asText.contains("BEGIN")) {
            String base64 = asText
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            der = Base64.getDecoder().decode(base64);
        } else {
            der = decoded; // already raw DER
        }
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }
}
