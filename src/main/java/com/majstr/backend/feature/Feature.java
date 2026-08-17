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
    RECEIPT_IMPORT,
    /** Recognise a hand-drawn room sketch photo into measurement rooms/elements via LLM
     *  vision (PRO+). Builds a draft the master verifies against our redrawn schema before
     *  it becomes real measurements — nothing is created without confirmation. */
    SKETCH_IMPORT,
    /** Import a designer's project documentation (PDF sheets / photos) into measurement
     *  rooms with a package of elements (floor/ceiling/walls/skirting) per room (PRO+).
     *  Text-layer extraction first, vision only for scans; the LLM never computes geometry. */
    PROJECT_IMPORT,
    /** Work acts (Акти виконаних робіт): a document built from a signed estimate's positions,
     *  signed separately by the client (acts iteration). Temporarily on FREE alongside
     *  {@link #OBJECT_ECONOMY}/{@link #MEASUREMENTS} — see {@code PlanConfig}. No LLM involved. */
    WORK_ACTS
}
