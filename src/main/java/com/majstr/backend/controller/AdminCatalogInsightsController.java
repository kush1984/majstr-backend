package com.majstr.backend.controller;

import com.majstr.backend.dto.AdminCatalogTemplateRequest;
import com.majstr.backend.dto.AdminCatalogTemplateResponse;
import com.majstr.backend.dto.CatalogInsights;
import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AdminCatalogInsightsService;
import com.majstr.backend.service.AdminCatalogTemplateService;
import com.majstr.backend.service.PriceInsightService;
import com.majstr.backend.service.catalog.CatalogNameKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What masters do with the catalog, read back as a lesson about our defaults (ROLE_ADMIN only).
 *
 * <p>Three read-only lists plus two actions — promote (with edits) and dismiss. See
 * {@link AdminCatalogInsightsService} for what each list means; the short version is that this
 * is an instrument for improving the default catalog, not a queue for approving submissions.
 */
@RestController
@RequestMapping("/api/admin/catalog-insights")
@RequiredArgsConstructor
@Tag(name = "Admin catalog insights",
        description = "Learn from master-created positions and templates (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminCatalogInsightsController {

    private final AdminCatalogInsightsService insights;
    private final AdminCatalogTemplateService templates;
    private final PriceInsightService priceInsights;

    @Operation(summary = "Positions masters created that the defaults do not cover",
            description = "Ranked by how many masters independently arrived at the same thing. "
                    + "Prices are aggregates across accounts — no master's own price is exposed.")
    @GetMapping("/new-positions")
    public List<CatalogInsights.NewPosition> newPositions() {
        return insights.newPositions();
    }

    @Operation(summary = "Positions we DO ship that masters worded differently",
            description = "Evidence our own naming is unusable — they could not find ours, so "
                    + "they typed their own. Usually the fix is to rename ours, not add theirs.")
    @GetMapping("/reworded")
    public List<CatalogInsights.RenamedPosition> reworded() {
        return insights.rewordedPositions();
    }

    @Operation(summary = "Default positions no estimate has ever resembled",
            description = "APPROXIMATE by construction: estimate lines are snapshots with no link "
                    + "back to the catalog, so usage is matched by name only. Good for 'nobody "
                    + "ever wrote anything like this', not a usage statistic.")
    @GetMapping("/unused")
    public List<CatalogInsights.UnusedDefault> unused() {
        return insights.unusedDefaults();
    }

    @Operation(summary = "Default positions whose community median price has drifted",
            description = "Two-level median (per-master, then across masters, outliers trimmed) "
                    + "from masters' own ESTIMATE lines, min 3 masters. Refreshed weekly — this "
                    + "reads the last refresh's snapshot, it does not recompute on open.")
    @GetMapping("/price-drift")
    public List<CatalogInsights.PriceDrift> priceDrift() {
        return priceInsights.listPriceDrift();
    }

    /**
     * Updates the shared default's price and queues a notice for every master whose own LIBRARY
     * item still carries the exact old price. Never touches an estimate (snapshots) or a
     * master's self-edited price.
     */
    @Operation(summary = "Apply a price-drift candidate to the default catalog")
    @PostMapping("/price-drift/apply")
    public ResponseEntity<Void> applyPriceDrift(@Valid @RequestBody ApplyPriceDriftRequest req,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        priceInsights.applyPriceDrift(req.candidateId(), principal.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * Add a reviewed candidate to the default catalog.
     *
     * <p>Takes the ADMIN's edited fields, never the master's raw row: the panel shows the exact
     * text that will be published so a template named after a client («Квартира Іванова») cannot
     * be handed to every master by a stray click. The master's price is not carried either —
     * `suggestedPrice` is whatever the admin decides.
     *
     * <p>Refused with 409 {@code DEFAULT_CATALOG_DUPLICATE} if an equivalent position already
     * exists (same words, different punctuation/order) — the guard lives in the create path so
     * it protects manual additions too.
     */
    @Operation(summary = "Promote a reviewed candidate into the default catalog")
    @PostMapping("/promote")
    public ResponseEntity<AdminCatalogTemplateResponse> promote(
            @Valid @RequestBody PromoteRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        AdminCatalogTemplateResponse created = templates.create(req.template(), principal.email());
        // It is in the defaults now; stop offering it as a candidate.
        insights.dismiss(CatalogInsightKind.NEW_POSITION, CatalogNameKey.of(req.template().name()),
                req.template().name(), principal.id(), "promoted to defaults");
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Stop showing a candidate",
            description = "Keyed by the normalised name, so one decision covers every spelling of "
                    + "the same work instead of returning once per master who typed it.")
    @PostMapping("/dismiss")
    public ResponseEntity<Void> dismiss(@Valid @RequestBody DismissRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        insights.dismiss(req.kind(), req.nameKey(), req.sampleName(), principal.id(), req.note());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bring a dismissed candidate back")
    @PostMapping("/undismiss")
    public ResponseEntity<Void> undismiss(@Valid @RequestBody UndismissRequest req) {
        insights.undismiss(req.kind(), req.nameKey());
        return ResponseEntity.noContent().build();
    }

    /** The admin's edited position — deliberately the same shape as a manual default add. */
    public record PromoteRequest(@NotNull @Valid AdminCatalogTemplateRequest template) {}

    public record DismissRequest(
            @NotNull CatalogInsightKind kind,
            @NotBlank String nameKey,
            @NotBlank String sampleName,
            String note) {}

    public record UndismissRequest(@NotNull CatalogInsightKind kind, @NotBlank String nameKey) {}

    public record ApplyPriceDriftRequest(@NotNull java.util.UUID candidateId) {}
}
