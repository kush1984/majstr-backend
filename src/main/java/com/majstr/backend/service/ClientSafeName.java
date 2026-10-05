package com.majstr.backend.service;

import java.util.regex.Pattern;

/**
 * An estimate's name as the CLIENT may read it (review B-74).
 *
 * <p>A duplicate made with a markup used to be named «Кошторис +20%» by default — by the PWA and by
 * {@code EstimateService.duplicateName} alike — and that name is printed on the client's portal page,
 * in the PDF and in the email subject. The client divides by 1,2 and has the crew's prices, which is
 * the one thing {@code PublicEstimateIsolationTest} exists to prevent; the leak simply travelled in a
 * string instead of a field.</p>
 *
 * <p>New copies are no longer named that way, but the ones already created are, so every public
 * surface strips a trailing rate rather than trusting the stored name. The pattern is deliberately
 * narrow — a trailing sign, digits and «%» — so «Фарбування 2 % розчином» keeps its name; and if
 * stripping leaves nothing, the original is returned, because an empty heading is worse than a hint.</p>
 */
final class ClientSafeName {

    /** A trailing «+20%», «-15,5 %», «−10%» — ASCII hyphen and the real minus sign both. */
    private static final Pattern RATE_SUFFIX =
            Pattern.compile("[\\s(\\[]*[+\\-−]\\s*\\d{1,3}([.,]\\d{1,2})?\\s*%[\\s)\\]]*$");

    private ClientSafeName() {
    }

    static String of(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String stripped = RATE_SUFFIX.matcher(name).replaceFirst("").trim();
        return stripped.isEmpty() ? name : stripped;
    }
}
