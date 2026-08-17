package com.majstr.backend.dto;

/**
 * Owner-side state of one act's share link (acts iteration). Unlike {@link PortalStateResponse}
 * (a SET of estimates), an act link is a single document: just its shareable URL ({@code null}
 * until the first publish mints it) and whether the act is currently shared (SENT or SIGNED).
 */
public record ActShareStateResponse(
        String url,
        boolean shared
) {}
