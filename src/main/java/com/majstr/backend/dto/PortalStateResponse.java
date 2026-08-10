package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owner-side state of the object's client portal: the shareable URL (null
 * until the first publish mints a link) and every estimate of the object with
 * its "shows on the portal" flag — the share sheet renders this as checkboxes.
 */
public record PortalStateResponse(
        String url,
        List<PortalEstimate> estimates,
        boolean paymentsVisible
) {
    public record PortalEstimate(
            UUID id,
            String name,
            EstimateStatus status,
            Instant createdAt,
            boolean visible
    ) {}
}
