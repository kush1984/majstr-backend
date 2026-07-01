package com.majstr.backend.controller;

import com.majstr.backend.dto.AdminCatalogTemplatePage;
import com.majstr.backend.dto.AdminCatalogTemplateRequest;
import com.majstr.backend.dto.AdminCatalogTemplateResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AdminCatalogTemplateService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin CRUD over the shared default catalog ({@code catalog_templates}). The
 * security chain gates {@code /api/admin/**} to {@code ROLE_ADMIN}; the admin HTML
 * panel drives these with a Bearer JWT.
 */
@RestController
@RequestMapping("/api/admin/catalog-templates")
@RequiredArgsConstructor
@Tag(name = "Admin catalog templates", description = "Edit the shared default catalog (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminCatalogTemplateController {

    private final AdminCatalogTemplateService service;

    @Operation(summary = "List/filter default catalog positions (paginated)")
    @GetMapping
    public AdminCatalogTemplatePage list(@RequestParam(required = false) Trade trade,
                                         @RequestParam(required = false) ItemType type,
                                         @RequestParam(required = false, name = "q") String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return service.list(trade, type, q, page, size);
    }

    @Operation(summary = "Create a default catalog position — stamped at the next catalog "
            + "version so existing masters get it via 'Add new from library'")
    @PostMapping
    public ResponseEntity<AdminCatalogTemplateResponse> create(
            @Valid @RequestBody AdminCatalogTemplateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, principal.email()));
    }

    @Operation(summary = "Edit a default catalog position — applies to new registrations; "
            + "masters who already copied it keep their own copy")
    @PutMapping("/{id}")
    public AdminCatalogTemplateResponse update(@PathVariable UUID id,
                                               @Valid @RequestBody AdminCatalogTemplateRequest req,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        return service.update(id, req, principal.email());
    }

    @Operation(summary = "Delete a default catalog position")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        service.delete(id, principal.email());
        return ResponseEntity.noContent().build();
    }
}
