package com.majstr.backend.billing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MonobankSignatureVerifierTest {

    private final MonobankSignatureVerifier verifier = new MonobankSignatureVerifier();

    @Test
    void verify_acceptsGenuineSignature_rejectsTamperedBody() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();

        byte[] body = "{\"invoiceId\":\"inv1\",\"status\":\"success\",\"amount\":29900}"
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(kp.getPrivate());
        signer.update(body);
        String xSign = Base64.getEncoder().encodeToString(signer.sign());
        // monobank returns the pubkey base64-encoded — mirror that (raw X.509 DER here).
        String pubKeyB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        assertThat(verifier.verify(body, xSign, pubKeyB64)).isTrue();

        byte[] tampered = "{\"invoiceId\":\"inv1\",\"status\":\"success\",\"amount\":1}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(tampered, xSign, pubKeyB64)).isFalse();
    }

    @Test
    void verify_rejectsBlankOrNullInputs() {
        assertThat(verifier.verify(null, "s", "k")).isFalse();
        assertThat(verifier.verify(new byte[]{1}, "", "k")).isFalse();
        assertThat(verifier.verify(new byte[]{1}, "s", "")).isFalse();
    }
}
