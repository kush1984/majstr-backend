package com.majstr.backend.service;

/**
 * The one key by which a position's NAME is matched across the product.
 *
 * <p>Catalog positions, bundle lines, estimate lines and consumption norms are joined by name and
 * nothing enforces that the spellings stay identical, so a name differing by a single space matched
 * nothing — and the line arrived priced at ZERO, with no error anywhere. Three call sites used to
 * disagree about this key: the map and the lookup lowercased without trimming while the dedup
 * trimmed. Collapsing runs of whitespace and the stray space after an opening bracket
 * («( плюс % до м.кв.») makes the join survive the untidiness real seed data has. V88 normalises
 * the stored names too; this is the belt to that migration's braces.</p>
 *
 * <p>It lives here, outside any one service, because the material calculator (V126) resolves norms
 * by the same key the template price resolution uses. Two private notions of "the same name" would
 * drift apart silently — the failure mode is a zero, never an error.</p>
 */
public final class NameKeys {

    private NameKeys() {}

    public static String of(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")")
                .trim().toLowerCase();
    }
}
