package com.majstr.backend.entity;

/** How an object expense was entered — drives the economy split. */
public enum ExpenseSource {
    /** Logged from a receipt import (real material cost) — counts against the deposit. */
    RECEIPT,
    /** Entered by hand — an unforeseen cost, subtracted from earnings. */
    MANUAL
}
