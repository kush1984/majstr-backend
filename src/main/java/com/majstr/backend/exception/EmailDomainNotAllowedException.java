package com.majstr.backend.exception;

/**
 * Registration (or an unverified email change) was attempted with an address whose domain is a known
 * disposable provider, or a domain no mail reaches. A 400 whose code tells the PWA which: a throwaway is
 * replaced, a typo is fixed, and the two are worded differently.
 */
public class EmailDomainNotAllowedException extends RuntimeException {

    public enum Reason {
        DISPOSABLE("EMAIL_DISPOSABLE", "error.email.disposable"),
        /** The domain exists without a mail exchanger, or does not exist at all: in practice, a typo. */
        NO_MAIL("EMAIL_NO_MAIL", "error.email.no-mail");

        private final String code;
        private final String messageKey;

        Reason(String code, String messageKey) {
            this.code = code;
            this.messageKey = messageKey;
        }

        public String code() {
            return code;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final Reason reason;

    public EmailDomainNotAllowedException(Reason reason) {
        super(reason.messageKey());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
