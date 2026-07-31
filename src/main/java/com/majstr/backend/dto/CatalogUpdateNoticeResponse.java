package com.majstr.backend.dto;

/**
 * The pending "your catalog was updated" notice, or {@code pending = false} when there is none.
 *
 * <p>A flag rather than a 404: "nothing to show" is the normal answer on nearly every app open,
 * and an error status for the normal case makes every client log noisy.</p>
 */
public record CatalogUpdateNoticeResponse(boolean pending, int added, int removed) {

    public static final CatalogUpdateNoticeResponse NONE =
            new CatalogUpdateNoticeResponse(false, 0, 0);
}
