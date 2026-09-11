package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.ShoppingListItemRequest;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListItemUpdateRequest;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.dto.ShoppingListSummaryResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ShoppingListService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The object's material shopping list. FREE on every plan — deciding how much to buy is not a
 * premium question. Owner-scoped in the service.
 *
 * <p>Everything here has to work with no network: a builders' merchant is a basement or a metal
 * shed. Ticking a row is the action the master performs there, so it is queued through the PWA's
 * outbox with {@code X-Entity-Uuid} on the create path.</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Shopping list", description = "Per-object material shopping list")
@SecurityRequirement(name = "bearer-jwt")
public class ShoppingListController {

    private final ShoppingListService shoppingListService;

    @Operation(summary = "Lists with something still to buy, for the home-screen card")
    @GetMapping("/shopping-lists/summary")
    public List<ShoppingListSummaryResponse> summary(@AuthenticationPrincipal UserPrincipal principal) {
        return shoppingListService.summaries(principal.id());
    }

    @Operation(summary = "The object's shopping list (an object with nothing to buy answers an empty list)")
    @GetMapping("/projects/{id}/shopping-list")
    public ShoppingListResponse get(@PathVariable UUID id,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return shoppingListService.get(id, principal.id());
    }

    @Operation(summary = "The list as a PDF, to send the client",
            description = "No prices: in the shop the price comes off the tag, and a figure on a "
                    + "sheet with the master's name on it would be read as a quote.")
    @GetMapping(value = "/projects/{id}/shopping-list/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserPrincipal principal)
            throws DocumentException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"shopping-list-" + id + ".pdf\"")
                .body(shoppingListService.renderPdf(id, principal.id()));
    }

    @Operation(summary = "Add a hand-written row",
            description = "An offline client may supply the row's UUID in the X-Entity-Uuid header — "
                    + "the create is then idempotent on replay.")
    @PostMapping("/projects/{id}/shopping-list/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ShoppingListItemResponse add(@PathVariable UUID id,
                                        @Valid @RequestBody ShoppingListItemRequest req,
                                        @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return shoppingListService.addManual(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a row — quantity, note, or the bought tick")
    @PatchMapping("/projects/{id}/shopping-list/items/{itemId}")
    public ShoppingListItemResponse update(@PathVariable UUID id,
                                           @PathVariable UUID itemId,
                                           @Valid @RequestBody ShoppingListItemUpdateRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return shoppingListService.update(id, principal.id(), itemId, req);
    }

    @Operation(summary = "Delete a row")
    @DeleteMapping("/projects/{id}/shopping-list/items/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID itemId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        shoppingListService.delete(id, principal.id(), itemId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Hide the bought rows",
            description = "Hides, never deletes: a deleted bought row is re-added by the next "
                    + "recalculation as unbought and the master buys the same material twice.")
    @PostMapping("/projects/{id}/shopping-list/clear-bought")
    public ShoppingListResponse clearBought(@PathVariable UUID id,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return shoppingListService.clearBought(id, principal.id());
    }
}
