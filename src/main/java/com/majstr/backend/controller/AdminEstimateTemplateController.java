package com.majstr.backend.controller;

import com.majstr.backend.dto.AdminEstimateTemplateRequest;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AdminEstimateTemplateService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over the shared DEFAULT estimate templates. Edits are live for every
 * master (defaults are applied straight from the DB, never copied). The security
 * chain gates {@code /api/admin/**} to {@code ROLE_ADMIN}; non-default (master-owned)
 * templates are rejected by the service.
 */
@RestController
@RequestMapping("/api/admin/estimate-templates")
@RequiredArgsConstructor
@Tag(name = "Admin estimate templates", description = "Edit the shared default estimate templates (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminEstimateTemplateController {

    private final AdminEstimateTemplateService service;

    @Operation(summary = "List all default estimate templates with item counts")
    @GetMapping
    public List<EstimateTemplateSummary> list() {
        return service.list();
    }

    @Operation(summary = "Get a default estimate template with its positions")
    @GetMapping("/{id}")
    public EstimateTemplateDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Create a default estimate template")
    @PostMapping
    public ResponseEntity<EstimateTemplateSummary> create(
            @Valid @RequestBody AdminEstimateTemplateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, principal.email()));
    }

    @Operation(summary = "Rename / re-trade a default estimate template")
    @PutMapping("/{id}")
    public EstimateTemplateSummary update(@PathVariable UUID id,
                                          @Valid @RequestBody AdminEstimateTemplateRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return service.update(id, req, principal.email());
    }

    @Operation(summary = "Delete a default estimate template (its items cascade)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        service.delete(id, principal.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a position to a default estimate template")
    @PostMapping("/{id}/items")
    public EstimateTemplateDetail addItem(@PathVariable UUID id,
                                          @Valid @RequestBody TemplateItemRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return service.addItem(id, req, principal.email());
    }

    @Operation(summary = "Edit a position in a default estimate template")
    @PutMapping("/{id}/items/{itemId}")
    public EstimateTemplateDetail updateItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @Valid @RequestBody TemplateItemRequest req,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return service.updateItem(id, itemId, req, principal.email());
    }

    @Operation(summary = "Remove a position from a default estimate template")
    @DeleteMapping("/{id}/items/{itemId}")
    public EstimateTemplateDetail removeItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return service.removeItem(id, itemId, principal.email());
    }
}
