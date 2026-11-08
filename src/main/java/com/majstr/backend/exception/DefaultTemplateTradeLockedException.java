package com.majstr.backend.exception;

/**
 * A system default asked to move to another trade (V194). A default lives under the trade it ships
 * for: re-filing it per master put a tiling bundle under a trade the master never picked, one tap in a
 * preview with no confirmation. Maps to 400 {@code TEMPLATE_DEFAULT_TRADE_LOCKED}.
 */
public class DefaultTemplateTradeLockedException extends RuntimeException {
    public DefaultTemplateTradeLockedException() {
        super("error.template.default-trade-locked");
    }
}
