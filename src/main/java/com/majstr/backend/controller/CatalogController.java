package com.majstr.backend.controller;

import com.majstr.backend.dto.AddTemplatesRequest;
import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogBulkDeleteRequest;
import com.majstr.backend.dto.CatalogBulkDeleteResponse;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.dto.CatalogItemsOrderRequest;
import com.majstr.backend.dto.CatalogResetResponse;
import com.majstr.backend.dto.CatalogUpdateNoticeResponse;
import com.majstr.backend.dto.TemplateUpdatesResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CatalogService;
import com.majstr.backend.service.CatalogTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Contractor's reusable library of works and materials")
@SecurityRequirement(name = "bearer-jwt")
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogTemplateService catalogTemplateService;
    private final UserRepository userRepository;

    @Operation(summary = "Create a catalog item",
            description = "Offline-authored creates may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the create is then idempotent on replay.")
    @PostMapping
    public ResponseEntity<CatalogItemResponse> create(
            @Valid @RequestBody CatalogItemRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalogService.create(req, principal.id(), entityId));
    }

    @Operation(summary = "List my catalog items in MY OWN arrangement (V87 sort_order; "
            + "alphabetical until I drag something), optionally filtered by type")
    @GetMapping
    public List<CatalogItemResponse> list(@RequestParam(required = false) ItemType type,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.listForOwner(principal.id(), type);
    }

    @Operation(summary = "List my distinct catalog categories for the picker/autocomplete")
    @GetMapping("/categories")
    public List<String> categories(@AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.categories(principal.id());
    }

    @Operation(summary = "Autocomplete: search my catalog by partial name (case-insensitive, owner-scoped), " +
            "optionally filtered by type. Returns up to `limit` (default 10, max 20) suggestions.")
    @GetMapping("/search")
    public List<CatalogItemResponse> search(@RequestParam("q") String q,
                                            @RequestParam(required = false) ItemType type,
                                            @RequestParam(required = false, defaultValue = "10") int limit,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.search(principal.id(), q, type, limit);
    }

    @Operation(summary = "Update a catalog item")
    @PutMapping("/{id}")
    public CatalogItemResponse update(@PathVariable UUID id,
                                      @Valid @RequestBody CatalogItemRequest req,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.update(id, req, principal.id());
    }

    @Operation(summary = "Delete a catalog item")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        catalogService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    // "/items" and "/{id}" both match DELETE /api/catalog/items — Spring picks the literal segment
    // over the template, which is what we want, but it is the kind of precedence worth stating
    // rather than rediscovering from a 400 that says "items is not a UUID".
    @Operation(summary = "Delete several catalog items at once — returns how many actually went")
    @DeleteMapping("/items")
    public CatalogBulkDeleteResponse deleteItems(@Valid @RequestBody CatalogBulkDeleteRequest req,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return new CatalogBulkDeleteResponse(catalogService.deleteItems(req.ids(), principal.id()));
    }

    @Operation(summary = "Persist the master's own arrangement of his catalog")
    @PatchMapping("/items/order")
    public List<CatalogItemResponse> reorder(@Valid @RequestBody CatalogItemsOrderRequest req,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.reorderItems(req, principal.id());
    }

    @Operation(summary = "Add starter templates for the user's trade. " +
            "Idempotent: items already present (by name + type + unit) are skipped.")
    @PostMapping("/reset-from-template")
    public CatalogResetResponse resetFromTemplate(@AuthenticationPrincipal UserPrincipal principal) {
        // Eager-fetch trades: resetForUser reads user.getTrades(), and this
        // detached entity would otherwise fail to lazy-init it (open-in-view off).
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        int added = catalogTemplateService.resetForUser(user);
        return new CatalogResetResponse(added);
    }

    @Operation(summary = "Add the starter set for specific trades to my catalog — a MERGE: "
            + "only missing items are added, existing items are never overwritten or duplicated. "
            + "Use after adding a trade to the profile.")
    @PostMapping("/add-from-template")
    public CatalogResetResponse addFromTemplate(@Valid @RequestBody AddTemplatesRequest req,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        // Eager-fetch trades (open-in-view off) — addTemplatesForTrades reads them.
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        int added = catalogTemplateService.addTemplatesForTrades(user, req.trades());
        return new CatalogResetResponse(added);
    }

    @Operation(summary = "How many NEW default-catalog items are available to add "
            + "(newer than last synced, my trades, not duplicates) — for the 'Add new' preview")
    @GetMapping("/template-updates")
    public TemplateUpdatesResponse templateUpdates(@AuthenticationPrincipal UserPrincipal principal) {
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        return new TemplateUpdatesResponse(catalogTemplateService.countNewFromCatalog(user));
    }

    @Operation(summary = "Add NEW default-catalog items (newer than last synced, my trades) — a MERGE: "
            + "never overwrites or duplicates existing items, never re-adds deleted/renamed ones")
    @PostMapping("/add-new-from-template")
    public CatalogResetResponse addNewFromTemplate(@AuthenticationPrincipal UserPrincipal principal) {
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        int added = catalogTemplateService.addNewFromCatalog(user);
        return new CatalogResetResponse(added);
    }

    @Operation(summary = "Every pending 'your catalog was updated' notice",
            description = "A queue, not a single slot — a master can have several at once (a "
                    + "migration's count notice, and/or a community price-drift notice per "
                    + "repriced position). Empty list is the normal 'nothing pending' answer.")
    @GetMapping("/update-notice")
    public List<CatalogUpdateNoticeResponse> updateNotices(@AuthenticationPrincipal UserPrincipal principal) {
        return catalogTemplateService.pendingUpdateNotices(principal.id());
    }

    @Operation(summary = "Mark one catalog-update notice as seen (declines a price-drift's proposal)")
    @PostMapping("/update-notice/{id}/dismiss")
    public ResponseEntity<Void> dismissUpdateNotice(@PathVariable UUID id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        catalogTemplateService.dismissUpdateNotice(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Accept a price-drift notice",
            description = "Updates the master's own catalog item to the new price — but only if "
                    + "it still carries the exact old price the notice named; a price the master "
                    + "edited themselves in the meantime is never touched. No-op price change (just "
                    + "a dismiss) on a plain count notice, which has nothing to accept.")
    @PostMapping("/update-notice/{id}/accept")
    public ResponseEntity<Void> acceptUpdateNotice(@PathVariable UUID id,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        catalogTemplateService.acceptUpdateNotice(principal.id(), id);
        return ResponseEntity.noContent().build();
    }
}
