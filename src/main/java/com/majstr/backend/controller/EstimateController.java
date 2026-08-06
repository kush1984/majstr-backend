package com.majstr.backend.controller;

import com.majstr.backend.dto.AddCatalogItemsBatchRequest;
import com.majstr.backend.dto.CountInEconomyRequest;
import com.majstr.backend.dto.EstimateConsolidateRequest;
import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemsDeleteRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemsOrderRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateSummary;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.ShareLinkResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.EstimateEmailRateLimiter;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ShareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Estimates", description = "Estimates and their line items")
@SecurityRequirement(name = "bearer-jwt")
public class EstimateController {

    private final EstimateService estimateService;
    private final ShareLinkService shareLinkService;
    private final EstimateEmailRateLimiter estimateEmailRateLimiter;

    // ---- estimates under a project ----------------------------------------

    @Operation(summary = "Create an estimate for a project",
            description = "Offline-authored creates may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the create is then idempotent on replay.")
    @PostMapping("/api/projects/{projectId}/estimates")
    public ResponseEntity<EstimateResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody EstimateCreateRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.createForProject(projectId, req, principal.id(), entityId));
    }

    @Operation(summary = "List estimates of a project")
    @GetMapping("/api/projects/{projectId}/estimates")
    public List<EstimateSummary> list(@PathVariable UUID projectId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.listForProject(projectId, principal.id());
    }

    @Operation(summary = "Consolidate several of the project's estimates into one new DRAFT estimate")
    @PostMapping("/api/projects/{projectId}/estimates/consolidate")
    public ResponseEntity<EstimateResponse> consolidate(@PathVariable UUID projectId,
                                                        @Valid @RequestBody EstimateConsolidateRequest req,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.consolidate(projectId, req.name(), req.estimateIds(), principal.id()));
    }

    // ---- single estimate ---------------------------------------------------

    @Operation(summary = "Get an estimate with its items and computed totals")
    @GetMapping("/api/estimates/{id}")
    public EstimateResponse get(@PathVariable UUID id,
                                @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.get(id, principal.id());
    }

    @Operation(summary = "Update an estimate's status / notes / validUntil")
    @PutMapping("/api/estimates/{id}")
    public EstimateResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody EstimateUpdateRequest req,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.update(id, req, principal.id());
    }

    @Operation(summary = "Delete an estimate (cascades to its items). Forbidden for SIGNED — reopen first.")
    @DeleteMapping("/api/estimates/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        estimateService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reopen a SIGNED estimate for edits (owner only) — clears the signature, "
            + "returns it to DRAFT; the client must sign again")
    @PostMapping("/api/estimates/{id}/reopen")
    public EstimateResponse reopen(@PathVariable UUID id,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.reopen(id, principal.id());
    }

    @Operation(summary = "Toggle whether this estimate counts toward the object's economy (income)")
    @PatchMapping("/api/estimates/{id}/count-in-economy")
    public EstimateResponse setCountInEconomy(@PathVariable UUID id,
                                              @Valid @RequestBody CountInEconomyRequest req,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.setCountInEconomy(id, req.countInEconomy(), principal.id());
    }

    @Operation(summary = "Download the estimate as a PDF, optionally appending chosen receipt photos")
    @GetMapping(value = "/api/estimates/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @RequestParam(name = "receipts", required = false) List<UUID> receipts,
                                      @AuthenticationPrincipal UserPrincipal principal)
            throws IOException, DocumentException {
        byte[] body = estimateService.renderPdf(id, principal.id(), receipts == null ? List.of() : receipts);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"estimate-" + id + ".pdf\"")
                .body(body);
    }

    // ---- share links ------------------------------------------------------

    @Operation(summary = "Create a public share link for the estimate")
    @PostMapping("/api/estimates/{id}/share")
    public ResponseEntity<ShareLinkResponse> createShareLink(@PathVariable UUID id,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shareLinkService.create(id, principal.id()));
    }

    @Operation(summary = "Email the share link to the estimate's client (creates a link if none yet; rate-limited)")
    @PostMapping("/api/estimates/{id}/share/send-email")
    public ResponseEntity<ShareLinkResponse> sendShareEmail(@PathVariable UUID id,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        EstimateEmailRateLimiter.ConsumeResult probe = estimateEmailRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.estimate-email", probe.retryAfterSeconds());
        }
        return ResponseEntity.ok(shareLinkService.sendByEmail(id, principal.id()));
    }

    @Operation(summary = "Revoke a share link so the public URL stops working")
    @DeleteMapping("/api/estimates/{id}/share/{linkId}")
    public ResponseEntity<Void> revokeShareLink(@PathVariable UUID id,
                                                @PathVariable UUID linkId,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        shareLinkService.revoke(id, linkId, principal.id());
        return ResponseEntity.noContent().build();
    }

    // ---- items -------------------------------------------------------------

    @Operation(summary = "Add a line item to an estimate",
            description = "Offline-authored adds may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the add is then idempotent on replay.")
    @PostMapping("/api/estimates/{estimateId}/items")
    public ResponseEntity<EstimateItemResponse> addItem(
            @PathVariable UUID estimateId,
            @Valid @RequestBody EstimateItemRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.addItem(estimateId, req, principal.id(), entityId));
    }

    @Operation(summary = "Add a line item by copying from a catalog entry",
            description = "Offline-authored adds may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the add is then idempotent on replay.")
    @PostMapping("/api/estimates/{estimateId}/items/from-catalog/{catalogItemId}")
    public ResponseEntity<EstimateItemResponse> addItemFromCatalog(
            @PathVariable UUID estimateId,
            @PathVariable UUID catalogItemId,
            @Valid @RequestBody EstimateItemFromCatalogRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.addItemFromCatalog(estimateId, catalogItemId, req, principal.id(), entityId));
    }

    @Operation(summary = "Add several catalog items at once (multi-select picker) — one "
            + "transaction. Rejected with 409 ESTIMATE_SIGNED on a signed estimate.")
    @PostMapping("/api/estimates/{estimateId}/items/batch")
    public ResponseEntity<EstimateResponse> addItemsFromCatalogBatch(
            @PathVariable UUID estimateId,
            @Valid @RequestBody AddCatalogItemsBatchRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.addItemsFromCatalogBatch(estimateId, req.items(), principal.id()));
    }

    @Operation(summary = "Update a line item")
    @PutMapping("/api/estimates/{estimateId}/items/{itemId}")
    public EstimateItemResponse updateItem(@PathVariable UUID estimateId,
                                           @PathVariable UUID itemId,
                                           @Valid @RequestBody EstimateItemRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.updateItem(estimateId, itemId, req, principal.id());
    }

    @Operation(summary = "Reorder the lines, and re-section them, after a drag")
    @PutMapping("/api/estimates/{estimateId}/items/order")
    public EstimateResponse reorderItems(@PathVariable UUID estimateId,
                                         @Valid @RequestBody EstimateItemsOrderRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.reorderItems(estimateId, req, principal.id());
    }

    @Operation(summary = "Delete a line item")
    @DeleteMapping("/api/estimates/{estimateId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID estimateId,
                                           @PathVariable UUID itemId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        estimateService.deleteItem(estimateId, itemId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete SEVERAL lines at once",
            description = "One request, one transaction — trimming a 167-position template is 130 "
                    + "deletions, and as many separate calls each carries its own chance of failing "
                    + "on a phone. Idempotent: ids already gone are skipped. Lines copied into a "
                    + "duplicate of this estimate go with them, unless that duplicate is SIGNED.")
    // POST, not DELETE-with-a-body: a request body on DELETE is legal but proxies and some HTTP
    // clients drop it, and losing the list silently would delete nothing while reporting success.
    @PostMapping("/api/estimates/{estimateId}/items/delete")
    public ResponseEntity<Void> deleteItems(@PathVariable UUID estimateId,
                                            @Valid @RequestBody EstimateItemsDeleteRequest req,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        estimateService.deleteItems(estimateId, req.itemIds(), principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Duplicate an estimate, marking the chosen lines up",
            description = "The бригадир's two-price workflow: the source keeps the crew's prices, "
                    + "the copy carries the client's. Only the markup counts as earnings in the "
                    + "object economy, and the source stops counting so wages are not reported as "
                    + "income. itemIds omitted = every WORK line, materials untouched.")
    @PostMapping("/api/estimates/{estimateId}/duplicate")
    public ResponseEntity<EstimateResponse> duplicate(@PathVariable UUID estimateId,
                                                      @Valid @RequestBody EstimateDuplicateRequest req,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.duplicate(estimateId, req, principal.id()));
    }
}
