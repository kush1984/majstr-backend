package com.majstr.backend.exception;

/**
 * The client tapped «Підписати» on a document that is no longer the one he was reading (review
 * B-61) — mapped to 409 with the code it carries.
 *
 * <p>A SENT act and an unsigned estimate both stay editable, and the signature endpoints used to
 * carry nothing identifying WHICH version was on the screen: the client opened «До сплати» 20 000,
 * the master added a 5 000 line while he read, and the tap signed 25 000 — a document the client
 * never saw, with his name and phone on it. So the portal now sends back the {@code version} it
 * rendered, and a mismatch is refused rather than resolved silently. It is not an error the client
 * caused and the wording says so: look again, then sign.</p>
 *
 * <p>Shared by both documents on purpose — the situation, the remedy and the HTTP answer are the
 * same; only the message key and the code differ.</p>
 */
public class DocumentChangedException extends RuntimeException {
    private final String code;

    public DocumentChangedException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
