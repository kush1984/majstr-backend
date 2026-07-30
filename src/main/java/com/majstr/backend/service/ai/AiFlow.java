package com.majstr.backend.service.ai;

/**
 * The recognition jobs, each of which may be worth a different model.
 *
 * <p>They are genuinely different tasks, not one task in different clothes. A receipt is a small
 * printed table read many times a day — cheap and fast is what matters. An A3 measure plan is dense
 * line-work read a few times per project, now in five passes, where the strongest vision available
 * pays for itself. Forcing both onto one model means either overpaying on every receipt or
 * under-reading every drawing.</p>
 *
 * <p>The album extractor is deliberately absent: it still owns its own HTTP client with longer
 * timeouts, because a whole-album pass runs for minutes. It joins this list when the seam learns to
 * carry a timeout.</p>
 */
public enum AiFlow {
    /** A contractor's estimate: a spreadsheet grid or a photo → priced positions. */
    ESTIMATE,
    /** A retail receipt photo → purchased items appended to an estimate. */
    RECEIPT,
    /** A hand-drawn room sketch photo → measurement rooms. */
    SKETCH,
    /** Electrical points counted off a plan against its legend. */
    ELECTRICAL,
    /** Designer's documentation sheets → rooms, geometry, openings. The heaviest of them. */
    PROJECT_DOCS,
    /**
     * Sorting a whole set's sheets by their titles, from text alone — the cheapest job here and the
     * one most worth putting on a small model: it reads no drawing, it only answers "what is this".
     */
    TRIAGE;

    /** The config key for this flow: {@code app.ai.flows.project-docs}. */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
