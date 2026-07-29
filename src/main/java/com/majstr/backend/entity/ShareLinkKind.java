package com.majstr.backend.entity;

/**
 * What a share link opens.
 *
 * <p>The distinction is a privacy boundary, not a label. {@link #PORTAL} shows the client their
 * estimate — prices, totals, the lot. {@link #MESSAGE} opens a form and nothing else, so a master can
 * send it to a supplier or a colleague without handing over what the client is being charged.</p>
 *
 * <p>Which means every lookup by token has to check this. Resolving a MESSAGE token as a portal would
 * show money to somebody who was only ever meant to type into a box.</p>
 */
public enum ShareLinkKind {
    PORTAL,
    MESSAGE
}
