package com.majstr.backend.controller;

import com.majstr.backend.dto.CatalogItemRequest;
import com.majstr.backend.dto.CatalogItemResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CatalogService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Contractor's reusable library of works and materials")
@SecurityRequirement(name = "bearer-jwt")
public class CatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "Create a catalog item")
    @PostMapping
    public ResponseEntity<CatalogItemResponse> create(@Valid @RequestBody CatalogItemRequest req,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.create(req, principal.id()));
    }

    @Operation(summary = "List my catalog items, optionally filtered by type")
    @GetMapping
    public List<CatalogItemResponse> list(@RequestParam(required = false) ItemType type,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return catalogService.listForOwner(principal.id(), type);
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
}
