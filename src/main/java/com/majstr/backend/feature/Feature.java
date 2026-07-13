package com.majstr.backend.feature;

/**
 * Catalog of gated features. Wired through {@link FeatureGuard}; the
 * default impl ({@link NoOpFeatureGuard}) lets everything through. When
 * paid plans land we swap the impl, no call-site changes needed.
 */
public enum Feature {
    BRANDED_PDF,
    CLIENT_PORTAL,
    ONLINE_SIGNATURE,
    PHOTO_REPORTS,
    AI_ASSISTANT,
    /** Per-object expense journal + real-profit summary (PRO+). Plan-gated, so an
     *  admin-granted dateless PRO qualifies. */
    OBJECT_ECONOMY,
    /** Import a ready estimate from an Excel/CSV file or a photo via LLM extraction
     *  (PRO+). Distinct from {@link #AI_ASSISTANT} (TEAM-only, reserved for "draft an
     *  estimate from a description") — this is a concrete PRO onboarding unlock. */
    ESTIMATE_IMPORT,
    /** Object measurements (Заміри): per-object rooms + measured elements, pulled into
     *  estimate line quantities. PRO+ — measuring pays off on big jobs (crews = PRO). */
    MEASUREMENTS,
    /** Add line items to an estimate from a receipt photo via LLM vision (PRO+). Distinct
     *  from {@link #ESTIMATE_IMPORT} (whole-estimate import + catalog upsert) — receipts
     *  only append material/work lines to the open estimate, never touching the catalog. */
    RECEIPT_IMPORT
}
