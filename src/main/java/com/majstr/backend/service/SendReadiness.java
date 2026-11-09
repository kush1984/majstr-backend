package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.SenderNameMissingException;

/**
 * What an account must have before a document leaves it for a client. One rule, two doors
 * (share-without-verification, 2026-11-09):
 *
 * <ul>
 *   <li>{@link #masterSendsItself} — the MASTER hands it over: a copied link, the phone's share sheet
 *       to Viber/Telegram, publishing an object portal or an act link, the estimate and act PDFs.
 *       Only a NAME is needed (V190), the one the document prints. The email is not asked: it is his
 *       own channel, and gating it lost the share step to 45 % of the masters who never verified.</li>
 *   <li>{@link #weSendTheLetter} — WE send a letter to his client from our domain (every
 *       {@code send-email}). A verified email AND a name: the letter rides our domain's reputation at
 *       Resend, and an unverified address is an account anyone could have typed. Email first, so a
 *       master missing both is told the older thing first.</li>
 * </ul>
 *
 * <p>The PRO trial, checkout, AI on FREE and changing the email keep their own verified-email checks
 * — they are not sends and do not come through here.</p>
 */
public final class SendReadiness {

    private SendReadiness() {}

    public static void masterSendsItself(User owner) {
        if (!ContractorName.hasName(owner)) {
            throw new SenderNameMissingException("error.sender-name-required");
        }
    }

    public static void weSendTheLetter(User owner) {
        if (!owner.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
        masterSendsItself(owner);
    }
}
