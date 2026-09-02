package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Create ONE estimate out of several bundles, taking <b>only the positions the master ticked</b>
 * in each of them.
 *
 * <p>The picked bundles used to ride a {@code ?ids=} query param beside a plain
 * {@link EstimateCreateRequest} body. A per-bundle subset cannot travel in a query string without
 * inventing an encoding for it, and splitting "which bundles" from "which of their positions"
 * across the query and the body would be two halves of one answer — so the whole thing is one
 * body now.</p>
 *
 * <p><b>{@code itemIds} absent (or empty) means the WHOLE bundle</b>, which is what every caller
 * that is not the picker means: the single-template endpoint, and an older client that never asked
 * about positions. Only a list that actually names positions narrows anything, so a bundle can
 * never silently contribute nothing.</p>
 */
public record ApplyTemplatesRequest(
        @NotEmpty @Valid List<TemplatePick> templates,
        /** The estimate's own fields (name / validUntil / notes) — may be absent. */
        @Valid EstimateCreateRequest estimate
) {
    public record TemplatePick(@NotNull UUID templateId, List<UUID> itemIds) {
    }

    /** Every position of every named bundle — the whole-bundle shorthand. */
    public static ApplyTemplatesRequest wholeBundles(List<UUID> templateIds, EstimateCreateRequest estimate) {
        return new ApplyTemplatesRequest(
                templateIds.stream().map(id -> new TemplatePick(id, null)).toList(), estimate);
    }

    /** Bundle ids in the order they were picked — that order decides whose wording survives a
     *  duplicate position, so it is kept. */
    public List<UUID> templateIds() {
        return templates.stream().map(TemplatePick::templateId).distinct().toList();
    }

    /**
     * Bundle id → the positions to take from it. A bundle whose pick names no position is absent
     * from the map, and the caller reads an absent key as «take everything».
     */
    public Map<UUID, Set<UUID>> pickedItemIds() {
        Map<UUID, Set<UUID>> picked = new HashMap<>();
        for (TemplatePick pick : templates) {
            if (pick.itemIds() == null || pick.itemIds().isEmpty()) {
                continue;
            }
            picked.computeIfAbsent(pick.templateId(), k -> new LinkedHashSet<>()).addAll(pick.itemIds());
        }
        return picked;
    }

    public EstimateCreateRequest estimateOrEmpty() {
        return estimate != null ? estimate : new EstimateCreateRequest(null, null, null);
    }
}
