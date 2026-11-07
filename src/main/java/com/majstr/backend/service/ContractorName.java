package com.majstr.backend.service;

import com.majstr.backend.entity.User;

/**
 * The one rule for what a document says about the master (V190). Name, phone and company are all
 * optional on the account; {@link SendReadiness} guarantees a NAME before anything leaves for a client.
 *
 * <ul>
 *   <li>{@link #display}: the header a client reads — the company, else the name.</li>
 *   <li>{@link #signer}: the person on a signature line — the name, else the company.</li>
 *   <li>{@link #phone}: the phone, or {@code null} when there is none — callers print no line then.</li>
 * </ul>
 *
 * <p>Every value is trimmed, and blank is {@code null}: a whitespace company must not hide the name.</p>
 */
public final class ContractorName {

    private ContractorName() {}

    public static String display(User user) {
        return display(user.getCompanyName(), user.getFullName());
    }

    /** For a frozen snapshot, which carries the two strings rather than the entity. */
    public static String display(String companyName, String fullName) {
        String company = clean(companyName);
        return company != null ? company : orEmpty(clean(fullName));
    }

    public static String signer(User user) {
        String name = clean(user.getFullName());
        return name != null ? name : orEmpty(clean(user.getCompanyName()));
    }

    public static String phone(User user) {
        return clean(user.getPhone());
    }

    /** Whether the account has a name a document can carry — the bar {@link SendReadiness} holds. */
    public static boolean hasName(User user) {
        return clean(user.getFullName()) != null;
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
