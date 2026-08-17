package com.majstr.backend.entity;

/**
 * The legal nature of a customer, which decides what requisites an act/estimate PDF prints for
 * them (acts iteration).
 *
 * <ul>
 *   <li>{@link #PERSON} — a private individual: only a name is needed;</li>
 *   <li>{@link #FOP} — приватний підприємець: name/legal name + РНОКПП + address;</li>
 *   <li>{@link #COMPANY} — юрособа: legal name + ЄДРПОУ + address + a signatory (title + name).</li>
 * </ul>
 */
public enum ClientType {
    PERSON,
    FOP,
    COMPANY
}
