package com.majstr.backend.entity;

/** Who may see an object photo. */
public enum PhotoVisibility {
    /** Master only — served via the authenticated owner endpoint. Receipts are always this. */
    PRIVATE,
    /** Shared with the client — served on the portal via the estimate's share token. */
    SHARED
}
