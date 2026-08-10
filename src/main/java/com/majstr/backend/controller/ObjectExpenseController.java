package com.majstr.backend.controller;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ObjectExpenseService;
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
 * Object economy: the per-object expense journal + a real-profit summary, PLUS (payments-
 * economy-portal iteration) FREE-visible per-estimate panels and the payment schedule.
 * {@code GET /economy} is reachable on every plan — only its {@code internals} field is
 * PRO-gated (soft, null for FREE). Every OTHER endpoint here (the expense journal itself) stays
 * hard PRO-gated ({@code Feature.OBJECT_ECONOMY}, FREE → 403 UPGRADE_REQUIRED). All owner-scoped
 * in the service. Owner-only — none of this reaches the client portal, PDF, or a share-token
 * response.
 */
@RestController
@RequestMapping("/api/projects/{id}")
@RequiredArgsConstructor
@Tag(name = "Object economy", description = "Per-object expenses + real profit (PRO)")
@SecurityRequirement(name = "bearer-jwt")
public class ObjectExpenseController {

    private final ObjectExpenseService expenseService;

    @Operation(summary = "Real profit for the object (income from estimates − expenses)")
    @GetMapping("/economy")
    public ObjectEconomyResponse economy(@PathVariable UUID id,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return expenseService.economy(id, principal.id());
    }

    @Operation(summary = "List the object's expenses (newest spend first)")
    @GetMapping("/expenses")
    public List<ExpenseResponse> list(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return expenseService.list(id, principal.id());
    }

    @Operation(summary = "Add an expense to the object")
    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse add(@PathVariable UUID id,
                               @Valid @RequestBody ExpenseRequest req,
                               @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return expenseService.add(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit an expense")
    @PatchMapping("/expenses/{expenseId}")
    public ExpenseResponse update(@PathVariable UUID id,
                                  @PathVariable UUID expenseId,
                                  @Valid @RequestBody ExpenseRequest req,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return expenseService.update(id, expenseId, principal.id(), req);
    }

    @Operation(summary = "Delete an expense")
    @DeleteMapping("/expenses/{expenseId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID expenseId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        expenseService.delete(id, expenseId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
