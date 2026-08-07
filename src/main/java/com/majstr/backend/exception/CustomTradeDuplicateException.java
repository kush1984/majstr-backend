package com.majstr.backend.exception;

/** The master already has a custom trade with this name (case-insensitive, trimmed —
 *  {@code ux_user_trade_owner_name}). */
public class CustomTradeDuplicateException extends RuntimeException {

    public CustomTradeDuplicateException(String name) {
        super("Custom trade already exists: " + name);
    }
}
