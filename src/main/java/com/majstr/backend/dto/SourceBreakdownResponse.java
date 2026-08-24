package com.majstr.backend.dto;

import java.util.List;

/**
 * Admin "by referral source" report — counts only, no money (a rev-share money layer comes with
 * billing). One row per source: the whole six-step activation funnel plus the two PRO-interest
 * counters, so the one question this report exists for can actually be answered — <b>which channel
 * brings masters who reach a SIGNED estimate</b>, not merely which channel brings sign-ups.
 *
 * <p><b>Invariant:</b> for every step, the sum over all sources equals the matching field of
 * {@link ActivationFunnelResponse}. Both sides count masters only and both take {@code shared} from
 * the same union of the two link tables. A test pins this — but note it catches the two reports
 * DISAGREEING, not both being wrong the same way.</p>
 *
 * <p><b>Read it with the threshold in hand.</b> Ranking sources by a percentage is meaningless at
 * small N: one registration that signs once is 100 % and would sit on top forever. Rows are
 * therefore split by {@code enoughData} ({@code registered >= significanceThreshold}); everything
 * below is a "not enough data" group that must not be compared with anything.</p>
 */
public record SourceBreakdownResponse(
        List<SourceStat> sources,
        /** {@code registered} a source needs before its percentages mean anything. Sent so the
         *  admin page can label the "not enough data" group without keeping its own copy of the
         *  number — two copies would drift the moment one is tuned. */
        int significanceThreshold,
        List<UtmStat> utm
) {

    /**
     * One referral source. {@code activated} is the object step ("has ≥1 object") and keeps its
     * original name — it predates the full funnel and renaming it would break existing readers for
     * cosmetics.
     */
    public record SourceStat(
            String source,
            long registered,
            long verifiedEmail,
            long activated,
            long withEstimate,
            long shared,
            long withSigned,
            long proClicks,
            long proInterested,
            /** {@code registered >= significanceThreshold} — computed here so the ordering and the
             *  page's grouping can never disagree about which rows are comparable. */
            boolean enoughData
    ) {}

    /**
     * One first-touch UTM source (V114) — the CHANNEL dimension, deliberately separate from the
     * partner dimension above (a master can follow a partner link from TikTok).
     *
     * <p>Only the two ends of the funnel: the question a channel is judged by is "% to signed", and
     * the middle steps are worth their four extra grouped queries only once there is volume.</p>
     *
     * <p>{@code source} is NULL for masters who arrived with no tags — a real bucket, rendered as
     * «без UTM», never as an empty cell.</p>
     */
    public record UtmStat(
            String source,
            long registered,
            long withSigned,
            boolean enoughData
    ) {}
}
