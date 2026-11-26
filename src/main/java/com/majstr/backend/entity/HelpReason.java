package com.majstr.backend.entity;

/** Why the «Допомога з налаштуванням» card is offered (V226) — and, once he asks, why he did. */
public enum HelpReason {
    /** A day in, and no object yet. */
    NO_OBJECT,
    /** A week in, an object, and nothing ever handed to a client — no estimate sent or signed, no PDF made. */
    NOT_SENT
}
