package com.majstr.backend.dto;

import java.util.List;

/**
 * Admin "by referral source" report — counts only, no money (a rev-share money
 * layer comes with billing). One row per source: how many masters registered,
 * how many activated (created an object), and — since upgrade_event exists — how
 * many clicked "Upgrade" / submitted PRO interest from that source.
 */
public record SourceBreakdownResponse(List<SourceStat> sources) {

    public record SourceStat(
            String source,
            long registered,
            long activated,
            long proClicks,
            long proInterested
    ) {}
}
