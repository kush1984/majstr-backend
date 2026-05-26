package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateSummary;
import com.majstr.backend.dto.EstimateUpdateRequest;
import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.ShareLinkResponse;
import com.majstr.backend.security.UserPrincipal;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    // ---- estimates under a project ----------------------------------------

    @Operation(summary = "Create an estimate for a project")
    @PostMapping("/api/projects/{projectId}/estimates")
    public ResponseEntity<EstimateResponse> create(@PathVariable UUID projectId,
                                                   @Valid @RequestBody EstimateCreateRequest req,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.createForProject(projectId, req, principal.id()));
    }

    @Operation(summary = "List estimates of a project")
    @GetMapping("/api/projects/{projectId}/estimates")
    public List<EstimateSummary> list(@PathVariable UUID projectId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.listForProject(projectId, principal.id());
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

    @Operation(summary = "Delete an estimate (cascades to its items)")
    @DeleteMapping("/api/estimates/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        estimateService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Download the estimate as a PDF")
    @GetMapping(value = "/api/estimates/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserPrincipal principal)
            throws IOException, DocumentException {
        byte[] body = estimateService.renderPdf(id, principal.id());
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

    @Operation(summary = "Revoke a share link so the public URL stops working")
    @DeleteMapping("/api/estimates/{id}/share/{linkId}")
    public ResponseEntity<Void> revokeShareLink(@PathVariable UUID id,
                                                @PathVariable UUID linkId,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        shareLinkService.revoke(id, linkId, principal.id());
        return ResponseEntity.noContent().build();
    }

    // ---- items -------------------------------------------------------------

    @Operation(summary = "Add a line item to an estimate")
    @PostMapping("/api/estimates/{estimateId}/items")
    public ResponseEntity<EstimateItemResponse> addItem(@PathVariable UUID estimateId,
                                                        @Valid @RequestBody EstimateItemRequest req,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.addItem(estimateId, req, principal.id()));
    }

    @Operation(summary = "Add a line item by copying from a catalog entry")
    @PostMapping("/api/estimates/{estimateId}/items/from-catalog/{catalogItemId}")
    public ResponseEntity<EstimateItemResponse> addItemFromCatalog(
            @PathVariable UUID estimateId,
            @PathVariable UUID catalogItemId,
            @Valid @RequestBody EstimateItemFromCatalogRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(estimateService.addItemFromCatalog(estimateId, catalogItemId, req, principal.id()));
    }

    @Operation(summary = "Update a line item")
    @PutMapping("/api/estimates/{estimateId}/items/{itemId}")
    public EstimateItemResponse updateItem(@PathVariable UUID estimateId,
                                           @PathVariable UUID itemId,
                                           @Valid @RequestBody EstimateItemRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return estimateService.updateItem(estimateId, itemId, req, principal.id());
    }

    @Operation(summary = "Delete a line item")
    @DeleteMapping("/api/estimates/{estimateId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID estimateId,
                                           @PathVariable UUID itemId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        estimateService.deleteItem(estimateId, itemId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
