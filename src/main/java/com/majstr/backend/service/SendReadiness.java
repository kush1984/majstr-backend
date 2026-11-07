package com.majstr.backend.service;

import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailNotVerifiedException;
import com.majstr.backend.exception.SenderNameMissingException;

/**
 * What an account must have before a document leaves it for a client: a verified email, and — since
 * registration stopped asking for it (V190) — a name for the document to carry.
 *
 * <p>One definition for every door that hands a client something: the per-estimate link, the object
 * portals and the act link with their letters ({@code ShareLinkService} / {@code ProjectPortalService}),
 * and the owner's estimate and act PDFs. Email first, so a master missing both is told the older thing
 * first, exactly as before this existed.</p>
 */
public final class SendReadiness {

    private SendReadiness() {}

    public static void require(User owner) {
        if (!owner.isEmailVerified()) {
            throw new EmailNotVerifiedException("error.email-not-verified");
        }
        requireName(owner);
    }

    /** The act PDF never asked for a verified email; it only needs the name its «Виконавець» prints. */
    public static void requireName(User owner) {
        if (!ContractorName.hasName(owner)) {
            throw new SenderNameMissingException("error.sender-name-required");
        }
    }
}
