package com.majstr.backend.exception;

/**
 * A price-list import couldn't be processed (unreadable file, or more rows than one
 * commit allows). The message is a bundle key resolved by the advice (→ 400), so the
 * master sees a friendly, localized reason.
 */
public class CatalogImportException extends RuntimeException {
    public CatalogImportException(String messageKey) {
        super(messageKey);
    }
}
