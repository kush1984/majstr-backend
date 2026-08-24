package com.majstr.backend.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a share link opens.
 *
 * <p>The distinction is a privacy boundary, not a label. {@link #PORTAL} shows the client their
 * estimate(s) for SIGNATURE — any status, prices, totals, never payments. {@link #ECONOMY} shows a
 * master-chosen set of already-SIGNED estimates plus a summary and (opt-in) a compact payments card
 * — a different intent, published from a different tab, on a different link, so the two can never
 * mix on one page. {@link #MESSAGE} opens a form and nothing else, so a master can send it to a
 * supplier or a colleague without handing over what the client is being charged. {@link #ACT} opens
 * exactly ONE work-completion act (Акт виконаних робіт) for the client to SIGN — deliberately NOT
 * folded into ECONOMY, which is read-only by design (it must never let the client sign): mixing the
 * two would revert to the old "one link, two intents" model. An ACT link carries its own
 * {@code work_act_id} (one link = one act), unlike the set-based PORTAL/ECONOMY links.</p>
 *
 * <p>Which means every lookup by token has to check this. Resolving a MESSAGE token as a portal would
 * show money to somebody who was only ever meant to type into a box; resolving an ECONOMY token with
 * PORTAL's rules would show a still-negotiating draft in what is supposed to be a settled-money view;
 * resolving an ACT token as anything else would let the client sign — or fail to sign — the wrong
 * document.</p>
 */
public enum ShareLinkKind {
    PORTAL,
    MESSAGE,
    ECONOMY,
    ACT;

    /**
     * The kinds that mean "the master showed a client a document" — the funnel's {@code shared} step
     * and the admin card's {@code hasShareLink}.
     *
     * <p>{@link #MESSAGE} is deliberately absent: it opens a contact form and nothing else, minted
     * for a supplier or a colleague. Counting it would award the step to somebody who never sent the
     * client anything. The set lives here rather than inline at the two call sites so the two can
     * never drift apart — a new kind gets classified once, in the place that defines what a kind is.</p>
     */
    public static final Set<ShareLinkKind> SHARED_WITH_CLIENT =
            Collections.unmodifiableSet(EnumSet.of(PORTAL, ECONOMY, ACT));
}
