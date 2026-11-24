package com.majstr.backend.dto;

import java.util.List;

/**
 * Why a SIGNED estimate cannot be reopened right now, sent WITH the estimate so the app says so
 * before the master taps (owner, 2026-11-24: «зразу показувати чому воно не можливо або що
 * зробити щоб було можливе»). Null on the response = it can be reopened (or is not signed).
 *
 * @param reason     the strongest thing in the way
 * @param actNumbers the acts to deal with, as printed («3», «П-1»); empty for {@code ADDENDUM}
 */
public record ReopenBlock(Reason reason, List<String> actNumbers) {

    public enum Reason {
        /** Written by a signed act («Додатково до акта № N») — never reopened. */
        ADDENDUM,
        /** Only draft acts: the master deletes them, then reopens. */
        DRAFT_ACT,
        /** An act is on the client's phone (no signed one): back to draft, delete, then reopen. */
        SENT_ACT,
        /** The client signed an act on it: changes go into a new estimate. */
        SIGNED_ACT
    }
}
