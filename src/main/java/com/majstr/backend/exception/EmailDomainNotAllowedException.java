package com.majstr.backend.exception;

/**
 * Registration (or an unverified email change) was attempted with an address whose
 * domain is a known disposable/temp-mail provider, or a domain that can't receive
 * mail (no MX/A record). Maps to 400 with code {@code EMAIL_DOMAIN_BLOCKED} so the
 * PWA can show a "use a real email" hint. The message carries a bundle key resolved
 * by the advice.
 */
public class EmailDomainNotAllowedException extends RuntimeException {
    public EmailDomainNotAllowedException(String message) {
        super(message);
    }
}
