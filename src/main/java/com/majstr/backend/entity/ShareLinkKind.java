package com.majstr.backend.entity;

/**
 * What a share link opens.
 *
 * <p>The distinction is a privacy boundary, not a label. {@link #PORTAL} shows the client their
 * estimate(s) for SIGNATURE — any status, prices, totals, never payments. {@link #ECONOMY} shows a
 * master-chosen set of already-SIGNED acts plus a summary and (opt-in) a compact payments card —
 * a different intent, published from a different tab, on a different link, so the two can never mix
 * on one page. {@link #MESSAGE} opens a form and nothing else, so a master can send it to a supplier
 * or a colleague without handing over what the client is being charged.</p>
 *
 * <p>Which means every lookup by token has to check this. Resolving a MESSAGE token as a portal would
 * show money to somebody who was only ever meant to type into a box; resolving an ECONOMY token with
 * PORTAL's rules would show a still-negotiating draft in what is supposed to be a settled-money view.</p>
 */
public enum ShareLinkKind {
    PORTAL,
    MESSAGE,
    ECONOMY
}
